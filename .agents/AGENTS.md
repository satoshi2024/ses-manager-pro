# Agent Behavioral Rules for SES Manager Pro

## File Encoding & Mojibake Prevention
- **Always use UTF-8 without BOM**: All source code, templates (HTML), scripts (JS), and configuration files in this project MUST be encoded in UTF-8 without BOM.
- **Prefer Built-in Tools**: When creating or modifying files, prioritize using the Agent's built-in `write_to_file`, `replace_file_content`, or `multi_replace_file_content` tools over custom terminal scripts, as the built-in tools handle UTF-8 safely.
- **PowerShell Encoding Caution**: If you absolutely must use PowerShell to write or modify files (e.g., to batch modify files), NEVER use the default `Out-File` or `Set-Content` without explicit encoding instructions. By default, Windows PowerShell 5.1 converts text to ANSI (Shift-JIS on Japanese Windows), which destroys non-ASCII characters and causes severe mojibake.
  - To safely write UTF-8 without BOM via PowerShell 5.1, use .NET methods instead of cmdlets:
    ```powershell
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText("path/to/file", $content, $utf8NoBom)
    ```
