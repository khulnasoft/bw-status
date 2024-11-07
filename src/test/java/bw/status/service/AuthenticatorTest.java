package bw.status.service;

import static io.undertow.util.StatusCodes.OK;
import static io.undertow.util.StatusCodes.UNAUTHORIZED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.Immutable;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import bw.status.testlib.HttpTester;
import bw.status.testlib.TestServicesInjector;

/**
 * Tests for {@link Authenticator}.
 */
@ExtendWith(TestServicesInjector.class)
public final class AuthenticatorTest {
  private static final String CORRECT_ACCOUNT_ID = "tester";
  private static final String CORRECT_PASSWORD = "password";
  private static final String WRONG_ACCOUNT_ID = "wrong_account";
  private static final String WRONG_PASSWORD = "wrong_password";
  private static final String NEW_ACCOUNT_ID = "new_account";
  private static final String NEW_PASSWORD = "new_password";
  private static final String TEMP_ACCOUNT_ID = "temp_account";
  private static final String TEMP_PASSWORD = "temp_password";
  private static final String IMPOSSIBLE_ACCOUNT_ID = "\0";

  /**
   * Verifies that {@link Authenticator#accountExists(String)} works as
   * expected.
   */
  @Test
  public void testAccountExists(Authenticator authenticator)
      throws IOException {

    assertTrue(authenticator.accountExists(CORRECT_ACCOUNT_ID));
    assertFalse(authenticator.accountExists(WRONG_ACCOUNT_ID));
    assertFalse(authenticator.accountExists(IMPOSSIBLE_ACCOUNT_ID));
  }

  /**
   * Verifies that {@link Authenticator#checkPassword(String, String)} works as
   * expected.
   */
  @Test
  public void testCheckPassword(Authenticator authenticator)
      throws IOException {

    assertTrue(authenticator.checkPassword(CORRECT_ACCOUNT_ID, CORRECT_PASSWORD));
    assertFalse(authenticator.checkPassword(CORRECT_ACCOUNT_ID, WRONG_PASSWORD));
    assertFalse(authenticator.checkPassword(WRONG_ACCOUNT_ID, CORRECT_PASSWORD));
    assertFalse(authenticator.checkPassword(IMPOSSIBLE_ACCOUNT_ID, CORRECT_PASSWORD));
  }

  /**
   * Verifies that {@link Authenticator#createAccountIfAbsent(String, String)}
   * and {@link Authenticator#deleteAccountIfPresent(String)} work as expected.
   */
  @Test
  public void testCreateAndDeleteAccount(Authenticator authenticator)
      throws IOException {

    assertFalse(authenticator.accountExists(NEW_ACCOUNT_ID));
    assertTrue(authenticator.createAccountIfAbsent(NEW_ACCOUNT_ID, NEW_PASSWORD));
    assertFalse(authenticator.createAccountIfAbsent(NEW_ACCOUNT_ID, NEW_PASSWORD));
    assertTrue(authenticator.checkPassword(NEW_ACCOUNT_ID, NEW_PASSWORD));

    assertThrows(
        IllegalArgumentException.class,
        () -> authenticator.createAccountIfAbsent(NEW_ACCOUNT_ID, WRONG_PASSWORD));

    assertTrue(authenticator.deleteAccountIfPresent(NEW_ACCOUNT_ID));
    assertFalse(authenticator.deleteAccountIfPresent(NEW_ACCOUNT_ID));
    assertFalse(authenticator.accountExists(NEW_ACCOUNT_ID));

    assertThrows(
        IllegalArgumentException.class,
        () -> authenticator.createAccountIfAbsent(IMPOSSIBLE_ACCOUNT_ID, CORRECT_PASSWORD));

    assertThrows(
        IllegalArgumentException.class,
        () -> authenticator.deleteAccountIfPresent(IMPOSSIBLE_ACCOUNT_ID));
  }

  /**
   * Verifies that {@link Authenticator}'s methods are thread-safe.
   */
  @Test
  public void testThreadSafety(Authenticator authenticator)
      throws InterruptedException, ExecutionException, TimeoutException {

    // TODO: How few resources can we use without invalidating the test?
    int numTasks = 64;
    int numThreads = 8;

    Callable<boolean[]> task =
        () -> new boolean[] {
            authenticator.accountExists(TEMP_ACCOUNT_ID),
            authenticator.createAccountIfAbsent(TEMP_ACCOUNT_ID, TEMP_PASSWORD),
            authenticator.checkPassword(TEMP_ACCOUNT_ID, TEMP_PASSWORD),
            authenticator.deleteAccountIfPresent(TEMP_ACCOUNT_ID)
        };

    List<Callable<boolean[]>> tasks = Collections.nCopies(numTasks, task);

    List<Future<boolean[]>> futures;

    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    try {
      futures = executor.invokeAll(tasks);
      executor.shutdown();
      executor.awaitTermination(1, TimeUnit.MINUTES);
    } finally {
      if (!executor.isTerminated()) {
        executor.shutdownNow();
      }
    }

    for (Future<boolean[]> future : futures) {
      future.get(0, TimeUnit.MILLISECONDS);
    }
  }

  /**
   * Verifies that {@link Authenticator#newRequiredAuthHandler(HttpHandler)}
   * accepts requests containing valid credentials and that {@link
   * Authenticator#requiredAccountId(HttpServerExchange)} makes the verified
   * account id available within the HTTP exchange.
   */
  @Test
  public void testNewRequiredAuthHandler_validCredentials(Authenticator authenticator,
                                                          HttpTester http)
      throws IOException, InterruptedException {

    HttpHandler handler =
        authenticator.newRequiredAuthHandler(
            exchange -> {
              String accountId = authenticator.requiredAccountId(exchange);
              exchange.getResponseSender().send(accountId);
            });

    String path = http.addHandler(handler);

    for (Account account : VALID_ACCOUNTS) {
      HttpResponse<String> response =
          http.client().send(
              http.addAuthorization(http.newRequestBuilder(path),
                                    account.accountId,
                                    account.password)
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(OK, response.statusCode());
      assertEquals(account.accountId, response.body());
    }
  }

  /**
   * Verifies that {@link Authenticator#newRequiredAuthHandler(HttpHandler)}
   * rejects requests containing no credentials.
   */
  @Test
  public void testNewRequiredAuthHandler_noCredentials(Authenticator authenticator,
                                                       HttpTester http)
      throws IOException, InterruptedException {

    HttpHandler handler =
        authenticator.newRequiredAuthHandler(
            exchange -> {
              String accountId = authenticator.requiredAccountId(exchange);
              exchange.getResponseSender().send(accountId);
            });

    String path = http.addHandler(handler);

    HttpResponse<String> response =
        http.client().send(
            http.newRequestBuilder(path).build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(UNAUTHORIZED, response.statusCode());
    assertEquals("", response.body());
  }

  /**
   * Verifies that {@link Authenticator#newRequiredAuthHandler(HttpHandler)}
   * rejects requests containing invalid credentials.
   */
  @Test
  public void testNewRequiredAuthHandler_badCredentials(Authenticator authenticator,
                                                        HttpTester http)
      throws IOException, InterruptedException {

    HttpHandler handler =
        authenticator.newRequiredAuthHandler(
            exchange -> {
              String accountId = authenticator.requiredAccountId(exchange);
              exchange.getResponseSender().send(accountId);
            });

    String path = http.addHandler(handler);

    for (Account account : INVALID_ACCOUNTS) {
      HttpResponse<String> response =
          http.client().send(
              http.addAuthorization(http.newRequestBuilder(path),
                                    account.accountId,
                                    account.password)
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(UNAUTHORIZED, response.statusCode());
      assertEquals("", response.body());
    }
  }

  /**
   * Verifies that {@link Authenticator#newRequiredAuthHandler(HttpHandler)}
   * accepts requests containing a mix of valid and invalid credentials.
   */
  @Test
  public void testNewRequiredAuthHandler_mixedCredentials(Authenticator authenticator,
                                                          HttpTester http)
      throws IOException, InterruptedException {

    HttpHandler handler =
        authenticator.newRequiredAuthHandler(
            exchange -> {
              String accountId = authenticator.requiredAccountId(exchange);
              exchange.getResponseSender().send(accountId);
            });

    String path = http.addHandler(handler);

    HttpRequest.Builder builder = http.newRequestBuilder(path);

    for (Account account : INVALID_ACCOUNTS) {
      builder = http.addAuthorization(builder,
                                      account.accountId,
                                      account.password);
    }

    for (Account account : VALID_ACCOUNTS) {
      builder = http.addAuthorization(builder,
                                      account.accountId,
                                      account.password);
    }

    HttpResponse<String> response =
        http.client().send(
            builder.build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response.statusCode());
  }

  /**
   * Verifies that {@link Authenticator#requiredAccountId(HttpServerExchange)}
   * throws an exception when used in an HTTP handler that was not wrapped by
   * {@link Authenticator#newRequiredAuthHandler(HttpHandler)}.
   */
  @Test
  public void testRequiredAccountId_noAuthentication(Authenticator authenticator,
                                                     HttpTester http)
      throws IOException, InterruptedException {

    String expectedMessage = "___NOT_AN_ACCOUNT_ID___";

    HttpHandler handler =
        exchange -> {
          String message;
          try {
            message = authenticator.requiredAccountId(exchange);
          } catch (IllegalStateException expected) {
            message = expectedMessage;
          }
          exchange.getResponseSender().send(message);
        };

    String path = http.addHandler(handler);

    HttpResponse<String> response =
        http.client().send(
            http.newRequestBuilder(path).build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response.statusCode());

    assertEquals(expectedMessage, response.body());
  }

  @Immutable
  private record Account(String accountId, String password) {

    Account {
      Objects.requireNonNull(accountId);
      Objects.requireNonNull(password);
    }
  }

  private static final ImmutableList<Account> VALID_ACCOUNTS =
      ImmutableList.of(
          new Account(CORRECT_ACCOUNT_ID, CORRECT_PASSWORD));

  private static final ImmutableList<Account> INVALID_ACCOUNTS =
      ImmutableList.of(
          new Account(CORRECT_ACCOUNT_ID, WRONG_PASSWORD),
          new Account(WRONG_ACCOUNT_ID, CORRECT_PASSWORD),
          new Account(WRONG_ACCOUNT_ID, WRONG_PASSWORD),
          new Account(IMPOSSIBLE_ACCOUNT_ID, CORRECT_PASSWORD),
          new Account(CORRECT_ACCOUNT_ID, ""),
          new Account("", CORRECT_PASSWORD),
          new Account("", ""));
}
