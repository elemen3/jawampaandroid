package g2.com.jawampaandroid;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;



public class MainActivity extends AppCompatActivity {


    //private String url = "ws://10.211.55.24:8080/ws";
    private String url = "ws://178.79.177.239:8080/ws";
    private String realm = "realm1";


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);



        try {
            new CrossbarExample(url,realm).run();
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }



    }
}
