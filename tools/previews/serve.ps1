# Serves docs/previews on http://localhost:8765, like serve.mjs, on a Windows machine without Node
# (`.claude/launch.json`, configuration « previews-ps »).
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\docs\previews"))
$types = @{ ".html" = "text/html; charset=utf-8"; ".png" = "image/png"; ".jpg" = "image/jpeg"; ".svg" = "image/svg+xml"; ".css" = "text/css"; ".js" = "text/javascript" }
$listener = [System.Net.HttpListener]::new()
$listener.Prefixes.Add("http://127.0.0.1:8765/")
$listener.Prefixes.Add("http://localhost:8765/")
$listener.Start()
Write-Output "previews on http://localhost:8765"
while ($listener.IsListening) {
    $context = $listener.GetContext()
    $path = [System.Uri]::UnescapeDataString($context.Request.Url.AbsolutePath)
    if ($path -eq "/") { $path = "/index.html" }
    $file = [System.IO.Path]::GetFullPath((Join-Path $root $path.TrimStart("/")))
    $response = $context.Response
    if (-not $file.StartsWith($root)) {
        $response.StatusCode = 403
    } elseif (Test-Path -LiteralPath $file -PathType Leaf) {
        $bytes = [System.IO.File]::ReadAllBytes($file)
        $type = $types[[System.IO.Path]::GetExtension($file)]
        $response.ContentType = if ($type) { $type } else { "application/octet-stream" }
        $response.OutputStream.Write($bytes, 0, $bytes.Length)
    } else {
        $response.StatusCode = 404
    }
    $response.Close()
}
