package com.example.demo.controllers;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.Spring;

@Controller
@RequestMapping("/")
public class MainController {

    private final MeterRegistry meterRegistry;
искусственная задержка в миллисекундах,
    private long delay = 0; 
лимит запросов 
    private int rateLimit = Integer.MAX_VALUE;
процент ошибок
    private int errorRate = 0;

обеспечивает потокобезопасность (атомарность - то есть либо операция выполнится целиком либо ваще не выполнится)
Аргумент - значение с которого стартуем 
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);

    private final Counter totalRequests;
    private final Counter failedRequests;
    private final Counter successfulRequests;

    public MainController(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

создаёт метрику типа Gauge — это «термометр», который при каждом запрошенном скрейпе вызывает указанный метод и берёт оттуда текущее значение.
        // Gauge метрики (динамические)
        meterRegistry.gauge("app_rate_limit", this, MainController::getRateLimit);
        meterRegistry.gauge("app_error_rate", this, MainController::getErrorRate);

counter(...) создаёт и возвращает объект Counter — нарастающий счётчик, значение которого увеличивается вызовом increment().
        // Счётчики (накапливаемые)
        totalRequests = meterRegistry.counter("app_requests_total");
        failedRequests = meterRegistry.counter("app_requests_failed");
        successfulRequests = meterRegistry.counter("app_requests_successful");
    }

Через model.addAttribute("имя", значение) он кладёт эти данные в модель.
Когда вы возвращаете строку 'index', Spring находит файл index.html и рендерит его, инжектя в шаблон все атрибуты из модели.
Ну крч шоб динамически рендерить хтмл
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
прилетает пост запрос на settings 
Позволяет получить значения из HTML-формы
            @RequestParam long delay,
            @RequestParam int rateLimit,
            @RequestParam int errorRate) {
присваивает текущие прилетевшие значения полям контроллера 
        this.delay = delay;
        this.rateLimit = rateLimit;
        this.errorRate = errorRate;
возвращает 302 редирект 
        return "redirect:/";
    }

    @Timed(value = "app_handle_json_time", description = "Время обработки запроса /api/json")
    @PostMapping("/api/json")
собирает все поля формы в словарь formData.
    public String handleJson(@RequestParam Map<String, String> formData, Model model) {
        totalRequests.increment();
        requestCount.incrementAndGet();
если число запросов превысило лимит запросов 
        if (requestCount.get() > rateLimit) {
+ 1 ошибка
            failedRequests.increment();
обновляем модель 
            populateModel(model);
добавляет сообщение об ошибке на хтмл
            model.addAttribute("errorMessage", "Rate limit exceeded");
            return "index";
        }
ну крч генерит случайное число ес оно меньше текущего процента ошибок 
        if (new Random().nextInt(100) < errorRate) {
            failedRequests.increment();
            populateModel(model);
в модель на странице добавляется сообщение об ошибке
            model.addAttribute("errorMessage", "Error occurred");
рендерится хтмл
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
дублирует логику прост вынесен в отдельный хелпер 
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