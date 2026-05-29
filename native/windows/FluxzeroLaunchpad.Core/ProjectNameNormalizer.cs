using System.Text.RegularExpressions;

namespace Fluxzero.Launchpad;

public static class ProjectNameNormalizer
{
    public static string Normalize(string name)
    {
        var cleaned = Regex.Replace(name.ToLowerInvariant(), @"[^a-z0-9\s_-]", "");
        var dashed = Regex.Replace(cleaned, @"[\s_]+", "-");
        return Regex.Replace(dashed, "-+", "-").Trim('-');
    }

    public static string PackageSuffix(string artifact)
    {
        var suffix = Regex.Replace(artifact.ToLowerInvariant(), @"[^a-z0-9]", "");
        return string.IsNullOrWhiteSpace(suffix) ? "app" : suffix;
    }
}
