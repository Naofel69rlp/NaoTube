# Starts the freshly built NaoTube.exe on the Windows build machine, drives it with the keyboard
# and takes screenshots, so every build can be checked visually.
param([string]$Exe)

$ErrorActionPreference = "Continue"
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
New-Item -ItemType Directory -Force -Path shots | Out-Null

function Shot($name) {
    $bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    $bmp = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
    $bmp.Save("shots\$name.png", [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
}

function Key($keys, $wait = 2) {
    [System.Windows.Forms.SendKeys]::SendWait($keys)
    Start-Sleep -Seconds $wait
}

$proc = Start-Process -FilePath $Exe -PassThru
Start-Sleep -Seconds 35
Shot "win-01-accueil"

# Bring the window to the front so the keys reach it.
$wshell = New-Object -ComObject wscript.shell
$null = $wshell.AppActivate($proc.Id)
Start-Sleep -Seconds 1

Key "{DOWN}" 3
Shot "win-02-rangee"
Key "{RIGHT}" 2
Shot "win-03-focus"
Key "{ENTER}" 12
Shot "win-04-fiche-video"
Key "{ENTER}" 25
Shot "win-05-lecteur"
Key "{ESC}" 4
Shot "win-06-retour"
Key "{ESC}" 3
Key "{LEFT}" 1
Key "{UP}" 1
Key "{ENTER}" 6
Shot "win-07-recherche"

if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force }
Start-Sleep -Seconds 2
Copy-Item "$env:APPDATA\NaoTube\naotube.log" "shots\naotube.log" -ErrorAction SilentlyContinue
exit 0
