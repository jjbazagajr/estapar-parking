package com.estapar.parking.config

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Profile
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.core.task.TaskExecutor

@TestConfiguration
@Profile("sync-async")
class SyncAsyncTestConfig {

    @Bean
    fun webhookExecutor(): TaskExecutor = SyncTaskExecutor()
}
