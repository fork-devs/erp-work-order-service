package com.erp.workorder.interfaces.rest.controller;

import com.erp.workorder.application.service.AddItemsToWorkOrderUseCase;
import com.erp.workorder.application.service.ApproveQuoteUseCase;
import com.erp.workorder.application.service.CancelWorkOrderUseCase;
import com.erp.workorder.application.service.OpenWorkOrderUseCase;
import com.erp.workorder.application.service.QuoteWorkOrderUseCase;
import com.erp.workorder.application.service.RejectQuoteUseCase;
import com.erp.workorder.application.service.TransitionWorkOrderUseCase;
import com.erp.workorder.domain.port.UnitOfWork;
import com.erp.workorder.domain.port.WorkOrderRepositoryPort;
import com.erp.workorder.infrastructure.config.I18nConfig;
import com.erp.workorder.interfaces.rest.advice.GlobalRestExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class I18nValidationTest {

    private MockMvc mockMvc;

    @Mock
    private OpenWorkOrderUseCase openUseCase;
    @Mock
    private AddItemsToWorkOrderUseCase addItemsUseCase;
    @Mock
    private TransitionWorkOrderUseCase transitionUseCase;
    @Mock
    private QuoteWorkOrderUseCase quoteUseCase;
    @Mock
    private ApproveQuoteUseCase approveUseCase;
    @Mock
    private RejectQuoteUseCase rejectUseCase;
    @Mock
    private CancelWorkOrderUseCase cancelUseCase;
    @Mock
    private WorkOrderRepositoryPort repository;
    @Mock
    private UnitOfWork unitOfWork;

    private static final String INVALID_PAYLOAD = """
            {
                "customerId": "00000000-0000-0000-0000-000000000001",
                "vehicleId": "00000000-0000-0000-0000-000000000002",
                "branchId": "00000000-0000-0000-0000-000000000003",
                "diagnosis": ""
            }
            """;

    @BeforeEach
    void setUp() {
        I18nConfig config = new I18nConfig();
        MessageSource messageSource = config.messageSource();
        LocalValidatorFactoryBean validator = config.getValidator(messageSource);
        validator.afterPropertiesSet();

        WorkOrderController controller = new WorkOrderController(
                openUseCase,
                addItemsUseCase,
                transitionUseCase,
                quoteUseCase,
                approveUseCase,
                rejectUseCase,
                cancelUseCase,
                repository,
                unitOfWork
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(validator)
                .setLocaleResolver(config.localeResolver())
                .setControllerAdvice(new GlobalRestExceptionHandler(messageSource))
                .build();
    }

    @Test
    @DisplayName("Accept-Language: es debe retornar mensaje de validación en español")
    void shouldReturnValidationErrorsInSpanish() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders")
                        .header("Accept-Language", "es")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INVALID_PAYLOAD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Error de Validación"))
                .andExpect(jsonPath("$.detail").value("diagnosis: El diagnóstico inicial es obligatorio."));
    }

    @Test
    @DisplayName("Accept-Language: en debe retornar mensaje de validación en inglés")
    void shouldReturnValidationErrorsInEnglish() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders")
                        .header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INVALID_PAYLOAD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.detail").value("diagnosis: Initial diagnosis is required."));
    }

    @Test
    @DisplayName("Sin header Accept-Language debe usar locale por defecto (es)")
    void shouldFallbackToDefaultLocaleWhenHeaderMissing() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INVALID_PAYLOAD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Error de Validación"))
                .andExpect(jsonPath("$.detail").value("diagnosis: El diagnóstico inicial es obligatorio."));
    }
}
