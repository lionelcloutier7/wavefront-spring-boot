package com.wavefront.springboot;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;

import static com.wavefront.springboot.WavefrontSpringBootAutoConfiguration.*;

/**
 * Spring {@link Condition} that decides whether the {@link WavefrontSpringBootAutoConfiguration} would offer a
 * {@link io.micrometer.wavefront.WavefrontConfig} bean. We need certain conditions to be met before a valid
 * Wavefront Configuration object can be returned successfully.
 *
 * @author Clement Pang (clement@wavefront.com).
 */
public class WavefrontConfigConditional extends SpringBootCondition {

  private static final Log logger = LogFactory.getLog(WavefrontConfigConditional.class);

  @Override
  public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
    Environment env = context.getEnvironment();
    // there are two methods to report wavefront observability data (proxy or http)
    // there are two methods to report wavefront observability data (proxy or http)
    String wavefrontUri = env.getProperty(PROPERTY_FILE_KEY_WAVEFRONT_INSTANCE, WAVEFRONT_DEFAULT_INSTANCE);
    boolean proxyReporting = wavefrontUri.startsWith("proxy://");
    @Nullable
    String wavefrontToken;
    if (!proxyReporting) {
      // we assume http reporting. defaults to wavefront.surf
      wavefrontToken = env.getProperty(PROPERTY_FILE_KEY_WAVEFRONT_TOKEN);
      if (wavefrontToken == null) {
        // attempt to read from local machine for the token to use.
        Optional<String> existingToken = getWavefrontTokenFromWellKnownFile();
        if (existingToken.isPresent()) wavefrontToken = existingToken.get();
      }
      if (wavefrontToken == null) {
        String applicationName = env.getProperty(PROPERTY_FILE_KEY_WAVEFRONT_APPLICATION,
            "springboot");
        String serviceName = env.getProperty(PROPERTY_FILE_KEY_WAVEFRONT_SERVICE,
            "unnamed_service");
        @Nullable
        String clusterName = env.getProperty(PROPERTY_FILE_KEY_WAVEFRONT_CLUSTER);
        @Nullable
        String shardName = env.getProperty(PROPERTY_FILE_KEY_WAVEFRONT_SHARD);
        if (!wavefrontUri.startsWith("http")) {
          wavefrontUri = "https://" + wavefrontUri;
        }
        RestTemplateBuilder restTemplateBuilder = new RestTemplateBuilder();
        RestTemplate restTemplate = restTemplateBuilder.build();
        UriComponentsBuilder uriComponentsBuilder = UriComponentsBuilder.
            fromUriString(wavefrontUri).path(
            "/api/v2/trial/spring-boot-autoconfigure").
            queryParam("application", applicationName).
            queryParam("service", serviceName);
        if (clusterName != null) {
          uriComponentsBuilder.queryParam("cluster", clusterName);
        }
        if (shardName != null) {
          uriComponentsBuilder.queryParam("shard", shardName);
        }
        if (logger.isDebugEnabled()) {
          logger.debug("Auto-negotiating Wavefront credentials from: " + wavefrontUri);
        }
        try {
          AccountProvisioningResponse resp = restTemplate.postForObject(
              uriComponentsBuilder.build().toUri(), null,
              AccountProvisioningResponse.class);
          if (resp == null) {
            logger.warn("Cannot auto-negotiate Wavefront credentials (null response), " +
                "cannot configure Wavefront Observability for Spring Boot");
            return ConditionOutcome.noMatch("Cannot auto-negotiate Wavefront credentials (null response)");
          }
          wavefrontToken = resp.getToken();
          Optional<String> written = writeWavefrontTokenToWellKnownFile(wavefrontToken);
          if (!written.isPresent()) {
            return ConditionOutcome.noMatch("Cannot write Wavefront credentials to disk");
          }
          logger.info("Auto-negotiation of Wavefront credentials successful, stored token can be found at: " +
              written.get());
          return ConditionOutcome.match("Auto-negotiation of Wavefront credentials successful");
        } catch (RuntimeException ex) {
          if (logger.isDebugEnabled()) {
            logger.debug("Runtime Exception in Wavefront auto-negotiation", ex);
          }
          logger.warn("Cannot auto-negotiate Wavefront credentials, cannot configure Wavefront" +
              " Observability for Spring Boot");
          return ConditionOutcome.noMatch("RuntimeException in Wavefront auto-negotiation");
        }
      } else {
        return ConditionOutcome.match("Existing Wavefront token found");
      }
    } else {
      return ConditionOutcome.noMatch("Proxy reporting for Wavefront configured");
    }
  }

  static Optional<String> writeWavefrontTokenToWellKnownFile(String token) {
    String userHomeStr = System.getProperty("user.home");
    if (userHomeStr == null || userHomeStr.length() == 0) {
      if (logger.isDebugEnabled()) {
        logger.debug("System.getProperty(\"user.home\") is empty, cannot write " +
            "local Wavefront token");
      }
      return Optional.empty();
    }
    try {
      File userHome = new File(userHomeStr);
      if (!userHome.exists()) {
        if (logger.isDebugEnabled()) {
          logger.debug("System.getProperty(\"user.home\") does not exist, cannot write " +
              "local Wavefront token");
        }
        return Optional.empty();
      }
      File wavefrontToken = new File(userHome, WAVEFRONT_TOKEN_FILENAME);
      if (!wavefrontToken.exists()) {
        Files.write(Paths.get(wavefrontToken.toURI()), token.getBytes(StandardCharsets.UTF_8));
        return Optional.of(wavefrontToken.getAbsolutePath());
      } else {
        return Optional.empty();
      }
    } catch (RuntimeException | IOException ex) {
      logger.warn("Cannot save Wavefront token to: " + userHomeStr + " directory. Cannot report " +
          "observability data without a valid token.", ex);
      return Optional.empty();
    }
  }
}
