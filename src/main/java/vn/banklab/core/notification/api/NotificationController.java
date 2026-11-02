package vn.banklab.core.notification.api;

import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import vn.banklab.core.notification.application.NotificationService;
import vn.banklab.core.notification.application.NotificationRepository;
import vn.banklab.core.shared.web.ApiErrorResponses;

@RestController
@RequestMapping(value="/api/v1/accounts/{accountId}/notifications",produces="application/json")
@ApiErrorResponses
public class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service) { this.service=service; }
    @GetMapping
    @Operation(summary="Read your latest in-app journal notifications",description="CUSTOMER source/destination account ownership required. Bounded latest list, limit 1–100 (default 50), newest occurredAt/eventId first; eventual consistency, not a balance or statement. Another account returns 404; OPERATOR-only returns 403. No email/SMS is sent.")
    public ResponseEntity<List<NotificationRepository.Notification>> latest(@PathVariable UUID accountId,@RequestParam(defaultValue="50") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.mine(accountId,limit));
    }
}
