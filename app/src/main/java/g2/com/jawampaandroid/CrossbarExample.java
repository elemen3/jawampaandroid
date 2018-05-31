package g2.com.jawampaandroid;

import rx.Scheduler;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.schedulers.Schedulers;
import ws.wamp.jawampa.ApplicationError;
import ws.wamp.jawampa.Request;
import ws.wamp.jawampa.WampClient;
import ws.wamp.jawampa.WampClientBuilder;
import ws.wamp.jawampa.connection.IWampConnectorProvider;
import ws.wamp.jawampa.transport.netty.NettyWampClientConnectorProvider;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A demo application that demonstrates all features of WAMP
 * and equals the demo applications of crossbar.io
 */
public class CrossbarExample {

    String url;
    String realm;

    WampClient client;

    Subscription addProcSubscription;
    Subscription counterPublication;
    Subscription onHelloSubscription;

    // Scheduler for this example
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Scheduler rxScheduler = Schedulers.from(executor);

    static final int TIMER_INTERVAL = 1000; // 1s
    int counter = 0;

    CrossbarExample(String url, String realm) throws Exception {
        this.url = url;
        this.realm = realm;
    }


    void run() {

        WampClientBuilder builder = new WampClientBuilder();
        IWampConnectorProvider connectorProvider = new NettyWampClientConnectorProvider();
        try {

            builder.withConnectorProvider(connectorProvider)
                    .withUri(url)
                    .withRealm(realm)
                    .withInfiniteReconnects()
                    .withCloseOnErrors(true)
                    .withReconnectInterval(5, TimeUnit.SECONDS);
            client = builder.build();
        } catch (Exception e) {
            System.out.println(e.getMessage());
            return;
        }

        // Subscribe on the clients status updates
        client.statusChanged()
                .observeOn(rxScheduler)
                .subscribe(new Action1<WampClient.State>() {
                    @Override
                    public void call(WampClient.State t1) {

                        System.out.println("Session status changed to " + t1);

                        if (t1 instanceof WampClient.ConnectedState) {

                            // SUBSCRIBE to a topic and receive events
                            onHelloSubscription = client.makeSubscription("com.example.onhello", String.class)
                                    .observeOn(rxScheduler)
                                    .subscribe(new Action1<String>() {
                                        @Override
                                        public void call(String msg) {
                                            System.out.println("event for 'onhello' received: " + msg);
                                        }
                                    }, new Action1<Throwable>() {
                                        @Override
                                        public void call(Throwable e) {
                                            System.out.println("failed to subscribe 'onhello': " + e);
                                        }
                                    }, new Action0() {
                                        @Override
                                        public void call() {
                                            System.out.println("'onhello' subscription ended");
                                        }
                                    });

                            // REGISTER a procedure for remote calling
                            addProcSubscription = client.registerProcedure("com.example.add2")
                                    .observeOn(rxScheduler)
                                    .subscribe(new Action1<Request>() {
                                        @Override
                                        public void call(Request request) {
                                            if (request.arguments() == null || request.arguments().size() != 2
                                                    || !request.arguments().get(0).canConvertToLong()
                                                    || !request.arguments().get(1).canConvertToLong())
                                            {
                                                try {
                                                    request.replyError(new ApplicationError(ApplicationError.INVALID_PARAMETER));
                                                } catch (ApplicationError e) { }
                                            }
                                            else {
                                                long a = request.arguments().get(0).asLong();
                                                long b = request.arguments().get(1).asLong();
                                                request.reply(a + b);
                                            }
                                        }
                                    }, new Action1<Throwable>() {
                                        @Override
                                        public void call(Throwable e) {
                                            System.out.println("failed to register procedure: " + e);
                                        }
                                    }, new Action0() {
                                        @Override
                                        public void call() {
                                            System.out.println("procedure subscription ended");
                                        }
                                    });

                            // PUBLISH and CALL every second .. forever
                            counter = 0;
                            counterPublication = rxScheduler.createWorker().schedulePeriodically(new Action0() {
                                @Override
                                public void call() {
                                    // PUBLISH an event
                                    final int published = counter;
                                    client.publish("com.example.oncounter", published)
                                            .observeOn(rxScheduler)
                                            .subscribe(new Action1<Long>() {
                                                @Override
                                                public void call(Long t1) {
                                                    System.out.println("published to 'oncounter' with counter " + published);
                                                }
                                            }, new Action1<Throwable>() {
                                                @Override
                                                public void call(Throwable e) {
                                                    System.out.println("Error during publishing to 'oncounter': " + e);
                                                }
                                            });

                                    // CALL a remote procedure
                                    client.call("com.example.mul2", Long.class, counter, 3)
                                            .observeOn(rxScheduler)
                                            .subscribe(new Action1<Long>() {
                                                @Override
                                                public void call(Long result) {
                                                    System.out.println("mul2() called with result: " + result);
                                                }
                                            }, new Action1<Throwable>() {
                                                @Override
                                                public void call(Throwable e) {
                                                    boolean isProcMissingError = false;
                                                    if (e instanceof ApplicationError) {
                                                        if (((ApplicationError) e).uri().equals("wamp.error.no_such_procedure"))
                                                            isProcMissingError = true;
                                                    }
                                                    if (!isProcMissingError) {
                                                        System.out.println("call of mul2() failed: " + e);
                                                    }
                                                }
                                            });

                                    counter++;
                                }
                            }, TIMER_INTERVAL, TIMER_INTERVAL, TimeUnit.MILLISECONDS);
                        }
                        else if (t1 instanceof WampClient.DisconnectedState) {
                            closeSubscriptions();
                        }
                    }
                }, new Action1<Throwable>() {
                    @Override
                    public void call(Throwable t) {
                        System.out.println("Session ended with error " + t);
                    }
                }, new Action0() {
                    @Override
                    public void call() {
                        System.out.println("Session ended normally");
                    }
                });

        client.open();
        waitUntilKeypressed();
        System.out.println("Shutting down");
        closeSubscriptions();
        client.close();
        try {
            client.getTerminationFuture().get();
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }

        executor.shutdown();
    }

    /**
     * Close all subscriptions (registered events + procedures)
     * and shut down all timers (doing event publication and calls)
     */
    void closeSubscriptions() {
        if (onHelloSubscription != null)
            onHelloSubscription.unsubscribe();
        onHelloSubscription = null;
        if (counterPublication != null)
            counterPublication.unsubscribe();
        counterPublication = null;
        if (addProcSubscription != null)
            addProcSubscription.unsubscribe();
        addProcSubscription = null;
    }

    private void waitUntilKeypressed() {
        try {
            System.in.read();
            while (System.in.available() > 0) {
                System.in.read();
            }
        } catch (IOException e) {
            System.out.println(e.getMessage());
            e.printStackTrace();
        }
    }

}