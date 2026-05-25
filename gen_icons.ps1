Add-Type -AssemblyName System.Drawing

$sizes = @(
    @{px=48;  dir="mdpi"},
    @{px=72;  dir="hdpi"},
    @{px=96;  dir="xhdpi"},
    @{px=144; dir="xxhdpi"},
    @{px=192; dir="xxxhdpi"}
)

foreach ($s in $sizes) {
    $bmp = New-Object System.Drawing.Bitmap($s.px, $s.px)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'HighQuality'
    $g.Clear([System.Drawing.Color]::FromArgb(33, 150, 243))

    $fontSize = [math]::Max(8, $s.px / 3)
    $font = New-Object System.Drawing.Font("Arial", $fontSize, [System.Drawing.FontStyle]::Bold)
    $brush = [System.Drawing.Brushes]::White
    $sf = New-Object System.Drawing.StringFormat
    $sf.Alignment = 'Center'
    $sf.LineAlignment = 'Center'
    $rect = New-Object System.Drawing.RectangleF(0, 0, $s.px, $s.px)
    $g.DrawString("GE", $font, $brush, $rect, $sf)

    $outPath = "D:\qclaw-workspace\android-guard-bot\app\src\main\res\mipmap-$($s.dir)\ic_launcher.png"
    $bmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)

    $g.Dispose()
    $bmp.Dispose()
    Write-Host "Saved $($s.px)px -> mipmap-$($s.dir)"
}

Write-Host "All icons generated."
