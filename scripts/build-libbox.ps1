param(
    [string]$SourceArchive = (Join-Path $PSScriptRoot '..\app\libs\libbox.SOURCE.zip'),
    [string]$OutputDirectory = (Join-Path $env:TEMP ('noki-libbox-' + [Guid]::NewGuid().ToString('N'))),
    [string]$GoRoot = '',
    [string]$GoArchivePath = '',
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$NdkRoot = '',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$PythonPath = 'python',
    [string]$ModuleCache = '',
    [string]$BuildCache = ''
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$SourceArchive = (Resolve-Path -LiteralPath $SourceArchive).Path
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $OutputDirectory) { throw "OutputDirectory already exists: $OutputDirectory" }
if (-not $AndroidSdk -or -not $JavaHome) { throw 'AndroidSdk and JavaHome (JDK 21) are required' }
if (-not $NdkRoot) { $NdkRoot = Join-Path $AndroidSdk 'ndk\29.0.14206865' }
$AndroidSdk = (Resolve-Path -LiteralPath $AndroidSdk).Path
$NdkRoot = (Resolve-Path -LiteralPath $NdkRoot).Path
$JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
$clang = Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe'
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null

function Invoke-Checked {
    param([string]$FilePath, [string[]]$Arguments)
    $savedPreference = $ErrorActionPreference
    try {
        # Native tools may write progress to stderr; only their exit code denotes failure.
        $ErrorActionPreference = 'Continue'
        if ([IO.Path]::GetFileName($FilePath) -eq 'gomobile.exe') {
            & $PythonPath -I $helperPath invoke $FilePath @Arguments
        } else { & $FilePath @Arguments }
        $nativeExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($nativeExitCode -ne 0) { throw "$FilePath failed with exit code $nativeExitCode" }
}

function Get-Sha256 {
    param([string]$Path)
    $stream = [IO.File]::OpenRead($Path)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($algorithm.ComputeHash($stream))).Replace('-', '').ToLowerInvariant()
    } finally {
        $algorithm.Dispose()
        $stream.Dispose()
    }
}

# Archive validation and deterministic AAR packaging use Python 3's standard library.
$archiveHelper = @'
from pathlib import Path
import hashlib,json,os,stat,struct,subprocess,sys,zipfile,zlib

mode,archive_path,output=sys.argv[1:4]
archive_path=Path(archive_path)
output=Path(output)
source=output/'source'
def sha(data): return hashlib.sha256(data).hexdigest()
if mode=='invoke':
    # Windows drive-directory entries (=C:) are not application environment variables.
    environment={k:v for k,v in os.environ.items() if not k.startswith('=')}
    raise SystemExit(subprocess.run(sys.argv[2:],env=environment).returncode)
elif mode=='extract':
    source.mkdir()
    with zipfile.ZipFile(archive_path) as archive:
        names=archive.namelist()
        assert len(names)==len(set(names)), 'duplicate archive entry'
        assert len(names)<=5000 and sum(item.file_size for item in archive.infolist())<=128*1024*1024, 'source archive exceeds supported size'
        for item in archive.infolist():
            path=(source/item.filename).resolve()
            assert path.is_relative_to(source.resolve()), 'archive path escapes source'
            assert not stat.S_ISLNK(item.external_attr >> 16), 'archive symlink'
            if item.is_dir():
                path.mkdir(parents=True,exist_ok=True)
            else:
                path.parent.mkdir(parents=True,exist_ok=True)
                path.write_bytes(archive.read(item))
    inputs=json.loads((source/'build-inputs.json').read_text())
    actual={'python':sys.version.split()[0],'zlib':zlib.ZLIB_VERSION,'zlib_runtime':zlib.ZLIB_RUNTIME_VERSION}
    assert actual==inputs['packaging'], 'Packaging runtime mismatch: expected '+str(inputs['packaging'])+', actual '+str(actual)
    assert inputs['source_commit']=='55faa763f986f4ca8a492d9b2719bc6330d2bef5'
    assert inputs['version'] in ('1.14.1-extended-2.7.2-nokinv-28','1.14.1-extended-2.7.2-nokinv-30','1.14.1-extended-2.7.2-nokinv-31','1.14.1-extended-2.7.2-nokinv-32','1.14.1-extended-2.7.2-nokinv-33','1.14.1-extended-2.7.2-nokinv-34')
    assert inputs['local_source_commit']=='0d157d028f75f0e5f554f5b0aa53863fce4a0a1c'
    assert inputs['abis']==['arm64-v8a','armeabi-v7a','x86_64']
    assert sha((source/'build-libbox.ps1').read_bytes())==inputs['recipe_sha256']
    assert inputs['android_api']==26
    assert inputs['tags']==['with_gvisor','with_quic','with_wireguard','with_utls','with_naive_outbound']
    assert sha((source/'src/go.mod').read_bytes())=='7a6d2e3d501fbf021c0a118152115c034a3a8d6a3d0517a7831a39b565accc8c'
    assert sha((source/'src/go.sum').read_bytes())=='7aebd46dd3d5c6d61dd479ae7297c8c35b569b54745fe0ca68c5466f7f7ed38a'
    for tree in inputs['trees']:
        root=source/tree['source']
        members=[p for p in sorted(root.rglob('*'),key=lambda p:p.relative_to(root).as_posix()) if p.is_file()]
        listing=[p.relative_to(root).as_posix()+'\0'+sha(p.read_bytes()) for p in members]
        assert len(members)==tree['files'] and sha('\n'.join(listing).encode())==tree['tree_sha256'], tree['source']
elif mode=='downloads':
    inputs=json.loads((source/'build-inputs.json').read_text())
    expected={(x['module'],x['version']):x['sum'] for x in inputs['modules']}
    expected[('github.com/sagernet/gomobile','v0.1.13')]=inputs['gomobile_generator_sum']
    remaining=(output/'module-downloads.json').read_text(encoding='utf-8-sig').strip()
    decoder=json.JSONDecoder()
    found={}
    while remaining:
        item,position=decoder.raw_decode(remaining)
        remaining=remaining[position:].lstrip()
        assert not item.get('Error'), item.get('Path')
        found[(item['Path'],item['Version'])]=item['Sum']
    assert found==expected, 'downloaded module h1 mismatch'
elif mode=='merge':
    shared=None
    members={}
    for abi,machine in [('arm64-v8a',183),('armeabi-v7a',40),('x86_64',62)]:
        with zipfile.ZipFile(output/('libbox-'+abi+'.aar')) as archive:
            current={name:archive.read(name) for name in archive.namelist() if not name.endswith('/') and not name.startswith('jni/')}
            assert shared is None or current==shared, 'ABI shared members differ'
            shared=current
            name='jni/'+abi+'/libbox.so'
            data=archive.read(name)
            assert data[:4]==b'\x7fELF' and struct.unpack('<H',data[18:20])[0]==machine, abi
            members[name]=data
    members.update(shared)
    assert b'minSdkVersion="26"' in members['AndroidManifest.xml']
    for name in ('LICENSE','COPYING','NOTICE'):
        members['assets/licenses/libbox/'+name]=(source/name).read_bytes()
    target=output/'libbox.aar'
    with zipfile.ZipFile(target,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as archive:
        for name,data in sorted(members.items()):
            item=zipfile.ZipInfo(name,(2026,10,1,0,0,0))
            item.compress_type=zipfile.ZIP_DEFLATED
            item.external_attr=0o100644 << 16
            archive.writestr(item,data,compresslevel=9)
    inputs=json.loads((source/'build-inputs.json').read_text())
    result={'source_sha256':sha(archive_path.read_bytes()),'aar_sha256':sha(target.read_bytes()),'aar_bytes':target.stat().st_size,'classes_sha256':sha(shared['classes.jar']),'members':{name:{'bytes':len(data),'sha256':sha(data)} for name,data in sorted(members.items())}}
    (output/'build-result.json').write_text(json.dumps(result,indent=2)+'\n')
    if inputs['aar_sha256'] is not None:
        assert result['aar_sha256']==inputs['aar_sha256'], 'AAR byte reproduction mismatch; output retained'
    else:
        assert inputs['version'] in ('1.14.1-extended-2.7.2-nokinv-30','1.14.1-extended-2.7.2-nokinv-31','1.14.1-extended-2.7.2-nokinv-32','1.14.1-extended-2.7.2-nokinv-33','1.14.1-extended-2.7.2-nokinv-34'), 'missing expected AAR checksum'
    print(json.dumps({k:v for k,v in result.items() if k!='members'}))
else:
    raise ValueError(mode)
'@
$helperPath = Join-Path $OutputDirectory 'package.py'
[IO.File]::WriteAllText($helperPath, $archiveHelper, (New-Object Text.UTF8Encoding($false)))
Invoke-Checked -FilePath $PythonPath -Arguments @('-I', $helperPath, 'extract', $SourceArchive, $OutputDirectory)
$sourceRoot = Join-Path $OutputDirectory 'source'
$inputs = Get-Content -LiteralPath (Join-Path $sourceRoot 'build-inputs.json') -Raw | ConvertFrom-Json
if ((Get-Sha256 $clang) -ne $inputs.clang_sha256) { throw 'NDK clang checksum mismatch' }
if (-not (Select-String -LiteralPath (Join-Path $NdkRoot 'source.properties') -Pattern 'Pkg.Revision\s*=\s*29\.0\.14206865' -Quiet)) { throw 'NDK revision mismatch' }
$previousPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $javaVersion = (& (Join-Path $JavaHome 'bin\java.exe') -version 2>&1) -join "`n"
} finally { $ErrorActionPreference = $previousPreference }
if ((Get-Sha256 (Join-Path $JavaHome 'bin\java.exe')) -ne $inputs.installed_tools.jdk.java_exe_sha256) { throw 'JDK executable checksum mismatch' }
if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch 'version "21\.') { throw 'JDK 21 is required' }

if (-not $GoRoot) {
    $goArchive = $inputs.tool_archives | Where-Object { $_.name -eq 'go' }
    if (-not $GoArchivePath) {
        $GoArchivePath = Join-Path $OutputDirectory $goArchive.filename
        Invoke-WebRequest -UseBasicParsing -TimeoutSec 120 -Uri $goArchive.url -OutFile $GoArchivePath
    }
    if ((Get-Sha256 $GoArchivePath) -ne $goArchive.actual_sha256) { throw 'Go archive checksum mismatch' }
    Expand-Archive -LiteralPath $GoArchivePath -DestinationPath (Join-Path $OutputDirectory 'toolchain')
    $GoRoot = Join-Path $OutputDirectory 'toolchain\go'
}
$GoRoot = (Resolve-Path -LiteralPath $GoRoot).Path
$go = Join-Path $GoRoot 'bin\go.exe'
if ((Get-Sha256 $go) -ne '5b25a4a434c7bbba11112a94c3fa9c1502c0f377bd971705ee8e07b8e3c12620') { throw 'Go executable checksum mismatch' }
$goVersion = (& $go version) -join "`n"
if ($LASTEXITCODE -ne 0 -or $goVersion -ne 'go version go1.26.9 windows/amd64') { throw 'Go 1.26.9 is required' }
if (-not $ModuleCache) { $ModuleCache = Join-Path $OutputDirectory 'gomodcache' }
if (-not $BuildCache) { $BuildCache = Join-Path $OutputDirectory 'gocache' }
$environmentNames = @('GOROOT','GOTOOLCHAIN','GOENV','GOWORK','GOPATH','GOBIN','GOMODCACHE','GOCACHE','GOTMPDIR','TMP','TEMP','GOFLAGS','GOMAXPROCS','GOSUMDB','GOPROXY','GONOSUMDB','GONOPROXY','GOPRIVATE','GODEBUG','GOOS','GOARCH','GOARM','CGO_ENABLED','JAVA_HOME','ANDROID_HOME','ANDROID_SDK_ROOT','ANDROID_NDK_HOME','PATH')
$savedEnvironment = @{}
foreach ($name in $environmentNames) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:GOROOT = $GoRoot; $env:GOTOOLCHAIN = 'local'; $env:GOENV = 'off'
    $env:GOWORK = Join-Path $sourceRoot 'go.work'
    $env:GOPATH = Join-Path $OutputDirectory 'gopath'; $env:GOBIN = Join-Path $env:GOPATH 'bin'
    $env:GOMODCACHE = [IO.Path]::GetFullPath($ModuleCache); $env:GOCACHE = [IO.Path]::GetFullPath($BuildCache)
    $env:GOTMPDIR = Join-Path $OutputDirectory 'tmp'; $env:TMP = $env:GOTMPDIR; $env:TEMP = $env:GOTMPDIR
    New-Item -ItemType Directory -Force -Path $env:GOBIN, $env:GOTMPDIR | Out-Null
    $env:GOFLAGS = '-mod=readonly -p=4'; $env:GOMAXPROCS = '4'; $env:GOSUMDB = 'sum.golang.org'
    $env:GOPROXY = 'https://proxy.golang.org,direct'
    $env:GONOSUMDB = ''; $env:GONOPROXY = ''; $env:GOPRIVATE = ''; $env:GODEBUG = ''
    $env:GOOS = 'windows'; $env:GOARCH = 'amd64'; $env:CGO_ENABLED = '0'; $env:GOARM = ''
    $env:JAVA_HOME = $JavaHome; $env:ANDROID_HOME = $AndroidSdk; $env:ANDROID_SDK_ROOT = $AndroidSdk; $env:ANDROID_NDK_HOME = $NdkRoot
    $env:PATH = (Join-Path $JavaHome 'bin') + ';' + (Join-Path $GoRoot 'bin') + ';' + $env:GOBIN + ';' + $env:PATH
    Push-Location (Join-Path $sourceRoot 'src')
    try {
        $modules = @($inputs.modules | ForEach-Object { "$($_.module)@$($_.version)" }) + @('github.com/sagernet/gomobile@v0.1.13')
        $downloads = & $go mod download -json @modules
        if ($LASTEXITCODE -ne 0) { throw 'Pinned module download failed' }
        [IO.File]::WriteAllText((Join-Path $OutputDirectory 'module-downloads.json'), ($downloads -join "`n"), (New-Object Text.UTF8Encoding($false)))
        Invoke-Checked -FilePath $PythonPath -Arguments @('-I', $helperPath, 'downloads', $SourceArchive, $OutputDirectory)
        if ((Get-Sha256 (Join-Path $sourceRoot 'src\go.mod')) -ne '7a6d2e3d501fbf021c0a118152115c034a3a8d6a3d0517a7831a39b565accc8c' -or
            (Get-Sha256 (Join-Path $sourceRoot 'src\go.sum')) -ne '7aebd46dd3d5c6d61dd479ae7297c8c35b569b54745fe0ca68c5466f7f7ed38a') { throw 'Pinned upstream module inputs changed' }
        Invoke-Checked -FilePath $go -Arguments @('mod','verify')
        $env:GOWORK = 'off'
        Invoke-Checked -FilePath $go -Arguments @('install','github.com/sagernet/gomobile/cmd/gomobile@v0.1.13')
        Invoke-Checked -FilePath $go -Arguments @('install','github.com/sagernet/gomobile/cmd/gobind@v0.1.13')
        $env:GOWORK = Join-Path $sourceRoot 'go.work'
        $env:GOOS = ''; $env:GOARCH = ''; $env:CGO_ENABLED = ''
        foreach ($bind in $inputs.commands.bind) {
            $env:GOARM = $bind.GOARM
            $arguments = @($bind.argv | Select-Object -Skip 1 | ForEach-Object {
                $_.Replace('<output>', $OutputDirectory.Replace('\','/')).Replace('<NDK>', $NdkRoot.Replace('\','/'))
            })
            Invoke-Checked -FilePath (Join-Path $env:GOBIN 'gomobile.exe') -Arguments $arguments
        }
    } finally { Pop-Location }
    Invoke-Checked -FilePath $PythonPath -Arguments @('-I', $helperPath, 'merge', $SourceArchive, $OutputDirectory)
    Copy-Item -LiteralPath $SourceArchive -Destination (Join-Path $OutputDirectory 'libbox.SOURCE.zip')
} finally {
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
}
