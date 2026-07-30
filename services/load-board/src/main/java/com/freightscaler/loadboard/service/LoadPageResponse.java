package com.freightscaler.loadboard.service;

import com.freightscaler.loadboard.model.Load;

import java.util.List;

public record LoadPageResponse(
        List<Load> loads,
        Long nextCursor,
        boolean hasMore
) {}
