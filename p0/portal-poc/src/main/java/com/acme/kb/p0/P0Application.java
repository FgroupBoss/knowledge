package com.acme.kb.p0;

import com.acme.kb.p0.config.P0Properties;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(P0Properties.class)
@MapperScan(basePackages = "com.acme.kb.p0.repo", annotationClass = Mapper.class)
public class P0Application {

    public static void main(String[] args) {
        SpringApplication.run(P0Application.class, args);
    }
}
