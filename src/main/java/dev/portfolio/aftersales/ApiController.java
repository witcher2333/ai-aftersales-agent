package dev.portfolio.aftersales;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
class ApiController {
    private final CaseService cases;
    private final PolicyService policies;

    ApiController(CaseService cases, PolicyService policies) {
        this.cases = cases;
        this.policies = policies;
    }

    private CurrentUser user(Authentication authentication) {
        return cases.current(authentication.getName());
    }

    @GetMapping("/me")
    CurrentUser me(Authentication authentication) { return user(authentication); }

    @GetMapping("/orders")
    List<OrderRecord> orders(Authentication authentication) { return cases.orders(user(authentication)); }

    @GetMapping("/orders/{id}")
    OrderRecord order(@PathVariable long id, Authentication authentication) {
        return cases.order(id, user(authentication));
    }

    @GetMapping("/cases")
    List<CaseRecord> tickets(Authentication authentication) { return cases.tickets(user(authentication)); }

    @GetMapping("/cases/{id}")
    CaseDetail detail(@PathVariable long id, Authentication authentication) {
        return cases.detail(id, user(authentication));
    }

    @PostMapping("/cases")
    CaseDetail create(@Valid @RequestBody CreateCaseRequest request, Authentication authentication) {
        return cases.create(request, user(authentication));
    }

    @PostMapping("/cases/{id}/take")
    CaseDetail take(@PathVariable long id, Authentication authentication) {
        return cases.take(id, user(authentication));
    }

    @PostMapping("/cases/{id}/decision")
    CaseDetail decide(@PathVariable long id, @Valid @RequestBody DecisionRequest request,
                      Authentication authentication) {
        return cases.decide(id, request, user(authentication));
    }

    @PostMapping("/cases/{id}/close")
    CaseDetail close(@PathVariable long id, Authentication authentication) {
        return cases.close(id, user(authentication));
    }

    @PostMapping("/cases/{id}/analysis/retry")
    CaseDetail retry(@PathVariable long id, Authentication authentication) {
        return cases.retryAnalysis(id, user(authentication));
    }

    @GetMapping("/policies")
    List<PolicyRecord> policies() { return policies.activePolicies(); }

    @PutMapping("/policies/{id}")
    PolicyRecord updatePolicy(@PathVariable long id, @Valid @RequestBody PolicyUpdateRequest request,
                              Authentication authentication) {
        if (!"AGENT".equals(user(authentication).role()))
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "仅客服可维护规则");
        return policies.update(id, request);
    }
}
