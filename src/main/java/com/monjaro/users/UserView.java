package com.monjaro.users;

import java.util.UUID;

public record UserView(UUID id, String username, String email) {}
