package ru.yandex.practicum.analyzer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import ru.yandex.practicum.analyzer.service.HubEventProcessor;

@Slf4j
@SpringBootApplication
@ConfigurationPropertiesScan
public class AnalyzerApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(AnalyzerApplication.class, args);
        HubEventProcessor hubEventProcessor = context.getBean(HubEventProcessor.class);
        Thread hubEventsThread = new Thread(hubEventProcessor, "HubEventHandlerThread");
        hubEventsThread.setUncaughtExceptionHandler((thread, error) -> {
            log.error("Обработка событий хабов остановлена из-за ошибки", error);
            context.close();
        });
        hubEventsThread.start();
    }
}
