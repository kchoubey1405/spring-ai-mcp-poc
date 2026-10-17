# download-deps.ps1
# Downloads Spring AI dependencies to local Maven cache via PowerShell
# (Maven's Java HTTP client times out on corporate networks, but PowerShell works)

$mavenBase = "https://repo.maven.apache.org/maven2"
$m2Repo = "$env:USERPROFILE\.m2\repository"

function Download-MavenArtifact {
    param(
        [string]$GroupId,
        [string]$ArtifactId,
        [string]$Version,
        [string]$Type = "jar"
    )
    
    $groupPath = $GroupId -replace '\.', '/'
    $basePath = "$m2Repo/$groupPath/$ArtifactId/$Version"
    $fileName = "$ArtifactId-$Version.$Type"
    $url = "$mavenBase/$groupPath/$ArtifactId/$Version/$fileName"
    $localPath = "$basePath/$fileName"
    
    if (Test-Path $localPath) {
        Write-Host "  [CACHED] $ArtifactId-$Version.$Type" -ForegroundColor DarkGray
        return $true
    }
    
    New-Item -ItemType Directory -Path $basePath -Force | Out-Null
    
    try {
        Write-Host "  [DOWNLOADING] $url" -ForegroundColor Yellow
        Invoke-WebRequest -Uri $url -OutFile $localPath -UseBasicParsing -TimeoutSec 60
        Write-Host "  [OK] $fileName" -ForegroundColor Green
        
        # Also download the POM if we downloaded a JAR
        if ($Type -eq "jar") {
            $pomUrl = "$mavenBase/$groupPath/$ArtifactId/$Version/$ArtifactId-$Version.pom"
            $pomPath = "$basePath/$ArtifactId-$Version.pom"
            if (-not (Test-Path $pomPath)) {
                Invoke-WebRequest -Uri $pomUrl -OutFile $pomPath -UseBasicParsing -TimeoutSec 60
                Write-Host "  [OK] $ArtifactId-$Version.pom" -ForegroundColor Green
            }
        }
        return $true
    } catch {
        Write-Host "  [FAILED] $fileName - $($_.Exception.Message)" -ForegroundColor Red
        return $false
    }
}

Write-Host "`n=== Downloading Spring AI 1.0.0 Dependencies ===" -ForegroundColor Cyan

$artifacts = @(
    # Spring AI Starters
    @{ g="org.springframework.ai"; a="spring-ai-starter-model-openai"; v="1.0.0"; t="jar" },
    @{ g="org.springframework.ai"; a="spring-ai-starter-model-openai"; v="1.0.0"; t="pom" },
    @{ g="org.springframework.ai"; a="spring-ai-starter-model-anthropic"; v="1.0.0"; t="jar" },
    @{ g="org.springframework.ai"; a="spring-ai-starter-model-anthropic"; v="1.0.0"; t="pom" },
    @{ g="org.springframework.ai"; a="spring-ai-starter-mcp-client"; v="1.0.0"; t="jar" },
    @{ g="org.springframework.ai"; a="spring-ai-starter-mcp-client"; v="1.0.0"; t="pom" },
    @{ g="org.springframework.ai"; a="spring-ai-starter-mcp-client-webflux"; v="1.0.0"; t="jar" },
    @{ g="org.springframework.ai"; a="spring-ai-starter-mcp-client-webflux"; v="1.0.0"; t="pom" },
    
    # Core Spring AI libs
    @{ g="org.springframework.ai"; a="spring-ai-openai"; v="1.0.0"; t="jar" },
    @{ g="org.springframework.ai"; a="spring-ai-openai"; v="1.0.0"; t="pom" },
    @{ g="org.springframework.ai"; a="spring-ai-anthropic"; v="1.0.0"; t="jar" },
    @{ g="org.springframework.ai"; a="spring-ai-anthropic"; v="1.0.0"; t="pom" },
    @{ g="org.springframework.ai"; a="spring-ai-mcp"; v="1.0.0"; t="jar" },
    @{ g="org.springframework.ai"; a="spring-ai-mcp"; v="1.0.0"; t="pom" },
    @{ g="org.springframework.ai"; a="spring-ai-client-chat"; v="1.0.0"; t="jar" },
    @{ g="org.springframework.ai"; a="spring-ai-client-chat"; v="1.0.0"; t="pom" },
    @{ g="org.springframework.ai"; a="spring-ai-model"; v="1.0.0"; t="jar" },
    @{ g="org.springframework.ai"; a="spring-ai-model"; v="1.0.0"; t="pom" },
    @{ g="org.springframework.ai"; a="spring-ai-commons"; v="1.0.0"; t="jar" },
    @{ g="org.springframework.ai"; a="spring-ai-commons"; v="1.0.0"; t="pom" },
    @{ g="org.springframework.ai"; a="spring-ai-retry"; v="1.0.0"; t="jar" },
    @{ g="org.springframework.ai"; a="spring-ai-retry"; v="1.0.0"; t="pom" },
    
    # Autoconfigure modules
    @{ g="org.springframework.ai"; a="spring-ai-autoconfigure-model-openai"; v="1.0.0"; t="jar" },
    @{ g="org.springframework.ai"; a="spring-ai-autoconfigure-model-openai"; v="1.0.0"; t="pom" },
    @{ g="org.springframework.ai"; a="spring-ai-autoconfigure-model-anthropic"; v="1.0.0"; t="jar" },
    @{ g="org.springframework.ai"; a="spring-ai-autoconfigure-model-anthropic"; v="1.0.0"; t="pom" },
    @{ g="org.springframework.ai"; a="spring-ai-autoconfigure-mcp-client"; v="1.0.0"; t="jar" },
    @{ g="org.springframework.ai"; a="spring-ai-autoconfigure-mcp-client"; v="1.0.0"; t="pom" },
    @{ g="org.springframework.ai"; a="spring-ai-autoconfigure-model-chat-client"; v="1.0.0"; t="jar" },
    @{ g="org.springframework.ai"; a="spring-ai-autoconfigure-model-chat-client"; v="1.0.0"; t="pom" },
    @{ g="org.springframework.ai"; a="spring-ai-autoconfigure-model-tool"; v="1.0.0"; t="jar" },
    @{ g="org.springframework.ai"; a="spring-ai-autoconfigure-model-tool"; v="1.0.0"; t="pom" },
    @{ g="org.springframework.ai"; a="spring-ai-autoconfigure-retry"; v="1.0.0"; t="jar" },
    @{ g="org.springframework.ai"; a="spring-ai-autoconfigure-retry"; v="1.0.0"; t="pom" },
    @{ g="org.springframework.ai"; a="spring-ai-autoconfigure-model-chat-observation"; v="1.0.0"; t="jar" },
    @{ g="org.springframework.ai"; a="spring-ai-autoconfigure-model-chat-observation"; v="1.0.0"; t="pom" },
    @{ g="org.springframework.ai"; a="spring-ai-autoconfigure-model-embedding-observation"; v="1.0.0"; t="jar" },
    @{ g="org.springframework.ai"; a="spring-ai-autoconfigure-model-embedding-observation"; v="1.0.0"; t="pom" },
    
    # Lombok
    @{ g="org.projectlombok"; a="lombok"; v="1.18.36"; t="jar" },
    @{ g="org.projectlombok"; a="lombok"; v="1.18.36"; t="pom" },
    
    # Spring Boot Maven Plugin (needed for build phase)
    @{ g="org.springframework.boot"; a="spring-boot-maven-plugin"; v="3.4.4"; t="jar" },
    @{ g="org.springframework.boot"; a="spring-boot-maven-plugin"; v="3.4.4"; t="pom" }
)

$success = 0
$failed = 0

foreach ($art in $artifacts) {
    $result = Download-MavenArtifact -GroupId $art.g -ArtifactId $art.a -Version $art.v -Type $art.t
    if ($result) { $success++ } else { $failed++ }
}

Write-Host "`n=== Summary ===" -ForegroundColor Cyan
Write-Host "Success: $success | Failed: $failed" -ForegroundColor $(if ($failed -gt 0) { "Yellow" } else { "Green" })
Write-Host "`nNow run: mvn compile -o" -ForegroundColor Cyan
