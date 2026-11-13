package com.example.fee.config

import com.example.fee.contract.FeeResponse
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The Caffeine cache of `ChargedFees`, sized by `fee-cache.*`. A bean of its own, so tests can empty it. */
@Configuration(proxyBeanMethods = false)
class ChargedFeesCacheConfig {
    @Bean
    fun chargedFeesCache(properties: FeeCacheProperties): Cache<String, FeeResponse> =
        Caffeine
            .newBuilder()
            .maximumSize(properties.maximumSize)
            .expireAfterWrite(properties.expireAfterWrite)
            .build()
}
