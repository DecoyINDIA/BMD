[Setup]
AppId={#TestAppId}
AppName=BMD updater integration test
AppVersion={#AppVersion}
DefaultDirName={#TestInstallDir}
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
DisableProgramGroupPage=yes
UsePreviousAppDir=yes
CloseApplications=no
Compression=lzma2/fast

[Files]
Source: "{#TestExe}"; DestDir: "{app}"; DestName: "bmd.exe"; Flags: ignoreversion

[Run]
Filename: "{app}\bmd.exe"; Parameters: "/updated"; Flags: nowait; Check: Relaunch

[Code]
function Relaunch: Boolean;
begin
  Result := ExpandConstant('{param:relaunch|0}') = '1';
end;
