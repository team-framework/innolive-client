namespace InnoLive_Windows.Services;

public static class ServerEnvironment
{
    private const string DefaultServerUrl = "https://innolive.duckdns.org";

    public static Uri HttpBaseUri => NormalizeHttpUri(Environment.GetEnvironmentVariable("INNOLIVE_SERVER_URL") ?? DefaultServerUrl);

    public static Uri SignalingUri
    {
        get
        {
            var overrideValue = Environment.GetEnvironmentVariable("INNOLIVE_SIGNALING_URL");
            if (!string.IsNullOrWhiteSpace(overrideValue)) return NormalizeWebSocketUri(overrideValue);
            var builder = new UriBuilder(HttpBaseUri) { Scheme = HttpBaseUri.Scheme == Uri.UriSchemeHttps ? "wss" : "ws", Path = "/signaling" };
            return builder.Uri;
        }
    }

    private static Uri NormalizeHttpUri(string value)
    {
        var candidate = value.Contains("://", StringComparison.Ordinal) ? value : $"https://{value}";
        var uri = new Uri(candidate, UriKind.Absolute);
        var builder = new UriBuilder(uri)
        {
            Scheme = uri.Scheme switch { "ws" => "http", "wss" => "https", _ => uri.Scheme },
            Path = string.Empty,
            Query = string.Empty,
            Fragment = string.Empty
        };
        return builder.Uri;
    }

    private static Uri NormalizeWebSocketUri(string value)
    {
        var uri = new Uri(value.Contains("://", StringComparison.Ordinal) ? value : $"wss://{value}", UriKind.Absolute);
        var builder = new UriBuilder(uri)
        {
            Scheme = uri.Scheme switch { "http" => "ws", "https" => "wss", _ => uri.Scheme },
            Path = string.IsNullOrWhiteSpace(uri.AbsolutePath) || uri.AbsolutePath == "/" ? "/signaling" : uri.AbsolutePath,
            Query = string.Empty,
            Fragment = string.Empty
        };
        return builder.Uri;
    }
}
