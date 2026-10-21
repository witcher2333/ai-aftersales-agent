package dev.portfolio.aftersales;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AfterSalesApplication {
    public static void main(String[] args) {
        SpringApplication.run(AfterSalesApplication.class, args);
    }
}
