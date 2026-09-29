package com.aatlas.notifications.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The bell: the latest notifications for the tenant, read state for the signed-in person. */
@RestController
@RequestMapping(path = "/api/v1/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Notifications", description = "What the bell shows.")
class NotificationsController {

    private final NotificationsService service;

    NotificationsController(NotificationsService service) {
        this.service = service;
    }

    @Operation(summary = "The latest notifications, newest first, and how many this person has not read")
    @GetMapping
    NotificationsService.View list() {
        return service.list();
    }

    @Operation(summary = "Mark one notification read for the signed-in person")
    @PostMapping("/{id}/read")
    ResponseEntity<Void> read(@PathVariable UUID id) {
        service.read(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Mark every notification read for the signed-in person")
    @PostMapping("/read-all")
    ResponseEntity<Void> readAll() {
        service.readAll();
        return ResponseEntity.noContent().build();
    }
}
