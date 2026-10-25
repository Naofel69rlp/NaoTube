; NaoTube — installateur Windows (Inno Setup 6)
; SPDX-License-Identifier: GPL-3.0-or-later
;
; Compiled by the GitHub build:  ISCC.exe /DAppVersion=1.0.N naotube.iss

#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif

[Setup]
AppId={{8F3C2A51-6D4E-4B7A-9C0D-1E2F3A4B5C6D}
AppName=NaoTube
AppVersion={#AppVersion}
AppVerName=NaoTube {#AppVersion}
AppPublisher=NaoTube
AppPublisherURL=https://github.com/Naofel69rlp/NaoTube
AppSupportURL=https://github.com/Naofel69rlp/NaoTube
DefaultDirName={localappdata}\Programs\NaoTube
DisableProgramGroupPage=yes
DisableDirPage=yes
DisableReadyPage=yes
PrivilegesRequired=lowest
OutputDir=..\build\installer
OutputBaseFilename=NaoTube-Setup
SetupIconFile=naotube.ico
UninstallDisplayIcon={app}\NaoTube.exe
UninstallDisplayName=NaoTube
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=force
RestartApplications=no

[Languages]
Name: "french"; MessagesFile: "compiler:Languages\French.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[InstallDelete]
; Updates replace the whole program (Java runtime, VLC) with the new one.
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "..\build\compose\binaries\main\app\NaoTube\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\NaoTube"; Filename: "{app}\NaoTube.exe"
Name: "{autodesktop}\NaoTube"; Filename: "{app}\NaoTube.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\NaoTube.exe"; Description: "{cm:LaunchProgram,NaoTube}"; Flags: nowait postinstall
