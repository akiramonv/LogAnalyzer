package io.github.loganalyzer.core.model;

/**
 * Категория аннотации, которую rule engine навешивает на событие таймлайна.
 * Используется и для подсветки в отчёте, и для взвешивания гипотез в анализаторе причин.
 */
public enum AnnotationType {
    /** Исключение/стек-трейс. */
    EXCEPTION,
    /** Ошибка уровня ERROR/FATAL без стека. */
    ERROR,
    /** Предупреждение. */
    WARNING,
    /** Повторная попытка (retry/backoff). */
    RETRY,
    /** Вызов внешней системы (HTTP, gRPC, очередь). */
    EXTERNAL_CALL,
    /** Таймаут любого рода. */
    TIMEOUT,
    /** Медленная операция (превышен порог длительности). */
    SLOW,
    /** Работа с БД: SQL, JDBC, JPA, пул соединений. */
    DATABASE,
    /** Очереди и брокеры: Kafka, RabbitMQ, JMS. */
    MESSAGING,
    /** Кэш: Redis, Caffeine, Hazelcast. */
    CACHE,
    /** Аутентификация/авторизация. */
    SECURITY,
    /** Конфигурация и контекст Spring. */
    CONFIGURATION,
    /** Ресурсы: память, потоки, файловые дескрипторы, диск. */
    RESOURCE,
    /** Circuit breaker / bulkhead / rate limiter. */
    RESILIENCE,
    /** Транзакции и блокировки. */
    TRANSACTION,
    /** Ошибка валидации входных данных. */
    VALIDATION,
    /** Ответ HTTP 4xx. */
    HTTP_CLIENT_ERROR,
    /** Ответ HTTP 5xx. */
    HTTP_SERVER_ERROR,
    /** Старт/остановка приложения. */
    LIFECYCLE,
    /** Событие, в котором нашёлся искомый реквизит (поиск по ИНН, id платежа, ФИО). */
    MATCH,
    /** Прочая пометка. */
    INFO
}
