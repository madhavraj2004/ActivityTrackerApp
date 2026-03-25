package com.fitwatch.app;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import com.fitwatch.app.MainActivity;
import com.fitwatch.app.R;

public class LoginActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState){

        super.onCreate(savedInstanceState);

        setContentView(R.layout.login);

        Button login = findViewById(R.id.loginBtn);

        login.setOnClickListener(v->{

            startActivity(new Intent(this, MainActivity.class));

        });

    }

}