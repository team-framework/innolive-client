using System.Net.Http.Json;
using System.Text.Json;

namespace InnoLive_Windows.Services;

public sealed class ServerDiagnosticsClient(HttpClient? httpClient = null)
{
    private readonly HttpClient _httpClient = httpClient ?? new HttpClient { Timeout = TimeSpan.FromSeconds(20) };

    public async Task<ServerDiagnosticResult> VerifyAsync(CancellationToken cancellationToken = default)
    {
        var baseUri = ServerEnvironment.HttpBaseUri;
        var healthUri = new Uri(baseUri, "/health");
        using var healthResponse = await _httpClient.GetAsync(healthUri, cancellationToken);
        healthResponse.EnsureSuccessStatusCode();

        var sessionUri = new Uri(baseUri, "/sessions");
        using var createResponse = await _httpClient.PostAsJsonAsync(sessionUri, new
        {
            metadata = new { title = "Windows connectivity diagnostic", broadcaster_id = "host", client = "innolive-windows" }
        }, cancellationToken);
        createResponse.EnsureSuccessStatusCode();

        using var sessionDocument = JsonDocument.Parse(await createResponse.Content.ReadAsStreamAsync(cancellationToken));
        if (!sessionDocument.RootElement.TryGetProperty("session_id", out var sessionProperty) || string.IsNullOrWhiteSpace(sessionProperty.GetString()))
            throw new InvalidOperationException("서버 응답에 session_id가 없습니다.");

        var sessionId = sessionProperty.GetString()!;
        var deleteUri = new Uri($"{sessionUri.AbsoluteUri.TrimEnd('/')}/{Uri.EscapeDataString(sessionId)}");
        using var deleteResponse = await _httpClient.DeleteAsync(deleteUri, cancellationToken);
        deleteResponse.EnsureSuccessStatusCode();

        return new ServerDiagnosticResult(baseUri, ServerEnvironment.SignalingUri, sessionId, healthResponse.StatusCode, deleteResponse.StatusCode);
    }
}

public sealed record ServerDiagnosticResult(Uri HttpBaseUri, Uri SignalingUri, string SessionId, System.Net.HttpStatusCode HealthStatus, System.Net.HttpStatusCode DeleteStatus);
