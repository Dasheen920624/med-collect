package com.medcollect;

import java.security.Principal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@Validated
class UserAccountController {
    private final UserAccounts users;

    UserAccountController(UserAccounts users) { this.users = users; }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    UserAccounts.Account register(@Valid @RequestBody UserAccounts.Credentials input) {
        return users.register(input);
    }

    @GetMapping("/users")
    Records.Page<UserAccounts.Account> list(@RequestParam(defaultValue = "") @Size(max = 80) String query,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return users.list(query, page, size);
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    UserAccounts.Account create(@Valid @RequestBody UserAccounts.Create input, Principal actor) {
        return users.create(input, actor.getName());
    }

    @PutMapping("/users/{id}")
    UserAccounts.Account update(@PathVariable String id, @Valid @RequestBody UserAccounts.Update input, Principal actor) {
        return users.update(id, input, actor.getName());
    }
}
