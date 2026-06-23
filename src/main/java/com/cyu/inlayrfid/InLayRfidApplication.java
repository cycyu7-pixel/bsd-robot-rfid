package com.cyu.inlayrfid;

import com.cyu.inlayrfid.config.RfidProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RfidProperties.class)
public class InLayRfidApplication {

    public static void main(String[] args) {
        SpringApplication.run(InLayRfidApplication.class, args);
    }

}