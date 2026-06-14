package io.fluxzero.examples;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class BootApplication {
    public static void main(String[] args) {
        SpringApplication.run(BootApplication.class, args);
    }

    @Bean
    CommandLineRunner runner(GreetingService greetingService) {
        return args -> System.out.println(greetingService.greeting(args.length == 0 ? "fluxzero" : args[0]));
    }
}
