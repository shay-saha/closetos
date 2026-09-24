package com.closetos.insights.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ForgottenRankingProperties.class)
class InsightConfiguration {}
