package com.example.demo.controllers;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Controller
@RequestMapping("/")
public class MainController {

    private final MeterRegistry meterRegistry;
    private long delay = 0; 
    private int rateLimit = Integer.MAX_VALUE;
    private int errorRate = 0;

    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);

    private final Counter totalRequests;
    private final Counter failedRequests;
    private final Counter successfulRequests;

    public MainController(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // Gauge метрики (динамические)
        meterRegistry.gauge("app_rate_limit", this, MainController::getRateLimit);
        meterRegistry.gauge("app_error_rate", this, MainController::getErrorRate);

        // Счётчики (накапливаемые)
        totalRequests = meterRegistry.counter("app_requests_total");
        failedRequests = meterRegistry.counter("app_requests_failed");
        successfulRequests = meterRegistry.counter("app_requests_successful");
    }

    @GetMapping
    public String index(Model model) {
        model.addAttribute("delay", delay);
        model.addAttribute("rateLimit", rateLimit);
        model.addAttribute("errorRate", errorRate);
        model.addAttribute("successRate", getSuccessRate());
        return "index";
    }

    @PostMapping("/settings")
    public String updateSettings(
            @RequestParam long delay,
            @RequestParam int rateLimit,
            @RequestParam int errorRate) {
        this.delay = delay;
        this.rateLimit = rateLimit;
        this.errorRate = errorRate;
        return "redirect:/";
    }

    @Timed(value = "app_handle_json_time", description = "Время обработки запроса /api/json")
    @PostMapping("/api/json")
    public String handleJson(@RequestParam Map<String, String> formData, Model model) {
        totalRequests.increment();
        requestCount.incrementAndGet();
        if (requestCount.get() > rateLimit) {
            failedRequests.increment();
            populateModel(model);
            model.addAttribute("errorMessage", "Rate limit exceeded");
            return "index";
        }
        if (new Random().nextInt(100) < errorRate) {
            failedRequests.increment();
            populateModel(model);
            model.addAttribute("errorMessage", "Error occurred");
            return "index";
        }

        successCount.incrementAndGet();
        successfulRequests.increment();

        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            model.addAttribute("errorMessage", "Sleep was interrupted");
            return "index";
        }

        populateModel(model);
        model.addAttribute("response", formData);
        return "index";
    }
    private void populateModel(Model model) {
        model.addAttribute("delay", delay);
        model.addAttribute("rateLimit", rateLimit);
        model.addAttribute("errorRate", errorRate);
        model.addAttribute("successRate", getSuccessRate());
    }

    public int getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(int rateLimit) {
        this.rateLimit = rateLimit;
    }

    public int getErrorRate() {
        return errorRate;
    }

    private double getSuccessRate() {
        if (requestCount.get() == 0) {
            return 0;
        }
        return (successCount.get() / (double) requestCount.get()) * 100;
    }
}