package com.epam.sdmxproxy.registry.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Slf4jFeignLogger extends feign.slf4j.Slf4jLogger {
    private final Logger logger;

    public Slf4jFeignLogger(Class<?> clazz) {
        this(LoggerFactory.getLogger(clazz));
    }

    public Slf4jFeignLogger(Logger logger) {
        super(logger);
        this.logger = logger;
    }

    @Override
    public void logRetry(String configKey, feign.Logger.Level logLevel) {
        if (logger.isWarnEnabled()) {
            logger.warn(String.format("%s ---> RETRYING", methodTag(configKey)));
        }
    }
}
