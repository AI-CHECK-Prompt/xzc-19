package com.coldchain.compliance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 跨境冷链 GxP 合规系统主入口。
 * <p>
 * 面向药监局飞行检查：覆盖温控数据上链式上传、不可篡改存储、规则引擎自动审计、
 * 放行决策闭环、飞检导出与时间轴回放。
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class ComplianceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ComplianceApplication.class, args);
    }
}
