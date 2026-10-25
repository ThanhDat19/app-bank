package vn.banklab.core.system.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "System", description = "Non-sensitive simulation metadata")
public class SystemController {

    @GetMapping("/info")
    @Operation(operationId = "getSystemInfo", summary = "Get simulation information",
            description = "Reports project scope. Use Actuator health to check database readiness.")
    @ApiResponse(responseCode = "200", description = "Simulation metadata")
    public SystemInfoResponse info() {
        return new SystemInfoResponse("core-banking-service", "SIMULATION");
    }
}
