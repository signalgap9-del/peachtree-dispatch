package com.atmospath.platform.risk;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping({"/api/v1", ""})
@ConditionalOnProperty(name = "atmospath.alert-stream.enabled", havingValue = "true", matchIfMissing = true)
public class AlertStreamController {
    private final AlertStreamService alertStream;

    public AlertStreamController(AlertStreamService alertStream) {
        this.alertStream = alertStream;
    }

    @GetMapping(value = "/alerts/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream() {
        return alertStream.connect();
    }
}
