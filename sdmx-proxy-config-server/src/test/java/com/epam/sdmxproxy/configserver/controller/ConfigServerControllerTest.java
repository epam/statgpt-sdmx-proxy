package com.epam.sdmxproxy.configserver.controller;

import com.epam.sdmxproxy.configserver.exception.ConfigServerExceptionHandler;
import com.epam.sdmxproxy.configserver.service.ConfigService;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConfigServerController.class)
@Import(ConfigServerExceptionHandler.class)
class ConfigServerControllerTest {

    private static final String CONFIG_PATH = "/statgpt/sdmx-proxy-config-server/api/v0/config";
    private static final String VALID_BODY = "{\"configs\":[],\"agencies\":[]}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConfigService configService;

    @Test
    void rejectedConfigurationAnswers422WithTheValidationMessage() throws Exception {
        Mockito.doThrow(new IllegalArgumentException("Agency 'IMF' references unknown registry 'UNKNOWN'")).when(configService).updateConfiguration(any());

        mockMvc.perform(post(CONFIG_PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value("Agency 'IMF' references unknown registry 'UNKNOWN'"));
    }

    @Test
    void storageWriteFailureIsNotTreatedAsValidationFailure() {
        Mockito.doThrow(new IllegalStateException("Failed to write configuration to DIAL Storage")).when(configService).updateConfiguration(any());

        // Unhandled by the advice, so MockMvc propagates it instead of rendering a 422 - in production this is Spring's default 500.
        Exception thrown = assertThrows(Exception.class, () -> mockMvc.perform(post(CONFIG_PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)));
        assertInstanceOf(IllegalStateException.class, NestedExceptionUtils.getMostSpecificCause(thrown));
    }

    @Test
    void acceptedConfigurationAnswers200() throws Exception {
        Mockito.doNothing().when(configService).updateConfiguration(any(ProxyConfiguration.class));

        mockMvc.perform(post(CONFIG_PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)).andExpect(status().isOk());
    }
}
