package se.alipsa.lca.api

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import se.alipsa.lca.shell.ShellCommands
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Date

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class RestSecurityFilterSpec extends Specification {

  ShellCommands commands = Stub() {
    health() >> "ok"
  }
  ShellCommandController controller = new ShellCommandController(commands)
  @TempDir
  Path tempDir

  def "blocks remote access when disabled"() {
    given:
    RestSecurityFilter filter = buildFilter(remoteEnabled: false)
    MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(filter).build()

    expect:
    mvc.perform(get("/api/cli/health")
      .with(remoteAddr("10.0.0.2"))
      .contentType(MediaType.APPLICATION_JSON))
      .andExpect(status().isForbidden())
  }

  def "requires api key when configured"() {
    given:
    RestSecurityFilter filter = buildFilter(apiKey: "secret")
    MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(filter).build()

    expect:
    mvc.perform(get("/api/cli/health")
      .with(remoteAddr("127.0.0.1")))
      .andExpect(status().isUnauthorized())

    mvc.perform(get("/api/cli/health")
      .with(remoteAddr("127.0.0.1"))
      .header("X-API-Key", "secret"))
      .andExpect(status().isOk())
  }

  def "rate limits when configured"() {
    given:
    RestSecurityFilter filter = buildFilter(maxPerMinute: 2)
    MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(filter).build()

    when:
    mvc.perform(get("/api/cli/health").with(remoteAddr("127.0.0.1")))
    mvc.perform(get("/api/cli/health").with(remoteAddr("127.0.0.1")))
    def third = mvc.perform(get("/api/cli/health").with(remoteAddr("127.0.0.1")))

    then:
    third.andExpect(status().isTooManyRequests())
  }

  def "requires https when enabled"() {
    given:
    RestSecurityFilter filter = buildFilter(requireHttps: true)
    MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(filter).build()

    expect:
    mvc.perform(get("/api/cli/health")
      .with(remoteAddr("10.0.0.2")))
      .andExpect(status().isForbidden())

    mvc.perform(get("/api/cli/health")
      .with(remoteAddr("10.0.0.2"))
      .with(secure(true)))
      .andExpect(status().isOk())
  }

  def "blocks remote access in local-only mode"() {
    given:
    RestSecurityFilter filter = buildFilter(localOnly: true, remoteEnabled: true)
    MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(filter).build()

    expect:
    mvc.perform(get("/api/cli/health")
      .with(remoteAddr("10.0.0.2"))
      .with(secure(true)))
      .andExpect(status().isForbidden())
  }

  def "accepts oidc tokens and enforces scopes"() {
    given:
    RSAKey key = new RSAKeyGenerator(2048).keyID("kid").generate()
    Path jwksPath = tempDir.resolve("jwks.json")
    Files.writeString(jwksPath, new JWKSet(key.toPublicJWK()).toString())
    RestSecurityFilter filter = buildFilter(
      oidcEnabled: true,
      oidcIssuer: "https://issuer.example",
      oidcAudience: "lca",
      oidcJwksFile: jwksPath.toString(),
      requiredReadScopes: "lca:read"
    )
    MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(filter).build()
    String validToken = buildToken(key, "https://issuer.example", "lca", "lca:read lca:write")
    String missingScope = buildToken(key, "https://issuer.example", "lca", "lca:write")

    expect:
    mvc.perform(get("/api/cli/health")
      .with(remoteAddr("127.0.0.1"))
      .header("Authorization", "Bearer ${validToken}"))
      .andExpect(status().isOk())

    mvc.perform(get("/api/cli/health")
      .with(remoteAddr("127.0.0.1"))
      .header("Authorization", "Bearer ${missingScope}"))
      .andExpect(status().isForbidden())
  }

  private static RestSecurityFilter buildFilter(Map args = [:]) {
    boolean localOnly = args.containsKey("localOnly") ? args.localOnly : false
    boolean remoteEnabled = args.containsKey("remoteEnabled") ? args.remoteEnabled : true
    boolean requireHttps = args.containsKey("requireHttps") ? args.requireHttps : false
    String apiKey = args.containsKey("apiKey") ? args.apiKey : ""
    String apiKeyScopes = args.containsKey("apiKeyScopes") ? args.apiKeyScopes : ""
    int maxPerMinute = args.containsKey("maxPerMinute") ? args.maxPerMinute : 0
    boolean oidcEnabled = args.containsKey("oidcEnabled") ? args.oidcEnabled : false
    String oidcIssuer = args.containsKey("oidcIssuer") ? args.oidcIssuer : ""
    String oidcAudience = args.containsKey("oidcAudience") ? args.oidcAudience : ""
    String oidcJwksFile = args.containsKey("oidcJwksFile") ? args.oidcJwksFile : ""
    String oidcJwksUri = args.containsKey("oidcJwksUri") ? args.oidcJwksUri : ""
    long oidcJwksTimeoutMillis = args.containsKey("oidcJwksTimeoutMillis") ? args.oidcJwksTimeoutMillis : 2000L
    String requiredReadScopes = args.containsKey("requiredReadScopes") ? args.requiredReadScopes : ""
    String requiredWriteScopes = args.containsKey("requiredWriteScopes") ? args.requiredWriteScopes : ""
    new RestSecurityFilter(
      localOnly,
      remoteEnabled,
      requireHttps,
      apiKey,
      apiKeyScopes,
      maxPerMinute,
      oidcEnabled,
      oidcIssuer,
      oidcAudience,
      oidcJwksFile,
      oidcJwksUri,
      oidcJwksTimeoutMillis,
      requiredReadScopes,
      requiredWriteScopes
    )
  }

  private static RequestPostProcessor remoteAddr(String addr) {
    return { request ->
      request.remoteAddr = addr
      request
    } as RequestPostProcessor
  }

  private static RequestPostProcessor secure(boolean value) {
    return { request ->
      request.secure = value
      request
    } as RequestPostProcessor
  }

  private static String buildToken(RSAKey key, String issuer, String audience, String scope) {
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
      .issuer(issuer)
      .audience(audience)
      .subject("user")
      .issueTime(new Date())
      .expirationTime(Date.from(Instant.now().plusSeconds(300)))
      .claim("scope", scope)
      .build()
    SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), claims)
    jwt.sign(new RSASSASigner(key))
    jwt.serialize()
  }
}
