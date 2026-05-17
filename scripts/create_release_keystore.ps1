$ErrorActionPreference = "Stop"

$keystoreDir = Join-Path $env:USERPROFILE ".keystores"
New-Item -ItemType Directory -Force -Path $keystoreDir | Out-Null
$keystorePath = Join-Path $keystoreDir "tube-next-release.jks"

$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()

function New-SecureToken([int]$bytes) {
    $buffer = New-Object byte[] $bytes
    $rng.GetBytes($buffer)
    return [Convert]::ToBase64String($buffer).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

$storePass = New-SecureToken 24
# Android/Java defaults to PKCS12 keystores on modern JDKs. PKCS12 does not
# support a separate key password, so keep both values identical.
$keyPass = $storePass

if (Test-Path $keystorePath) {
    throw "Keystore already exists: $keystorePath. Move or delete it explicitly if you want to rotate the release key."
}

keytool -genkeypair -v `
  -keystore "$keystorePath" `
  -alias tubenext `
  -keyalg RSA `
  -keysize 4096 `
  -validity 10000 `
  -storepass "$storePass" `
  -keypass "$keyPass" `
  -dname "CN=Tube NEXT, OU=Mobile, O=Tube NEXT, L=Berlin, ST=Berlin, C=DE" | Out-Null

if (!(Test-Path $keystorePath)) {
    throw "Keystore creation failed."
}

$storeFileForProps = $keystorePath.Replace("\", "/")
$keyPropsPath = Join-Path (Get-Location) "key.properties"
@"
storeFile=$storeFileForProps
storePassword=$storePass
keyAlias=tubenext
keyPassword=$keyPass
"@ | Set-Content -Path $keyPropsPath -Encoding Ascii

$fingerprint = (
    keytool -list -v -keystore "$keystorePath" -alias tubenext -storepass "$storePass" |
    Select-String -Pattern "SHA256:" |
    Select-Object -First 1
).ToString().Trim()

$infoPath = Join-Path $keystoreDir "tube-next-keepass.txt"
@"
Title: Tube NEXT Release Signing
KeystorePath: $keystorePath
StorePassword: $storePass
KeyAlias: tubenext
KeyPassword: $keyPass
CertificateSHA256: $fingerprint
CreatedAt: $(Get-Date -Format o)
"@ | Set-Content -Path $infoPath -Encoding UTF8

Write-Output "KEYSTORE_PATH=$keystorePath"
Write-Output "KEY_PROPERTIES_PATH=$keyPropsPath"
Write-Output "KEEPASS_INFO_PATH=$infoPath"
Write-Output "FINGERPRINT=$fingerprint"
Write-Output "IMPORTANT=Save the keepass info in KeePassXC, then delete the plaintext info file."
