Add-Type -AssemblyName System.Drawing

function New-SimpleIcon {
    param([string]$path, [int]$size)
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Clear([System.Drawing.Color]::FromArgb(30, 136, 229))
    $brush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
    $font = New-Object System.Drawing.Font("Arial", [int]($size * 0.45), [System.Drawing.FontStyle]::Bold)
    $sf = New-Object System.Drawing.StringFormat
    $sf.Alignment = [System.Drawing.StringAlignment]::Center
    $sf.LineAlignment = [System.Drawing.StringAlignment]::Center
    $rect = New-Object System.Drawing.RectangleF(0, 0, $size, $size)
    $g.DrawString("G", $font, $brush, $rect, $sf)
    $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

$base = "D:\qclaw-workspace\android-guard-bot\app\src\main\res"

New-SimpleIcon "$base\mipmap-hdpi\ic_launcher.png" 72
New-SimpleIcon "$base\mipmap-mdpi\ic_launcher.png" 48
New-SimpleIcon "$base\mipmap-xhdpi\ic_launcher.png" 96
New-SimpleIcon "$base\mipmap-xxhdpi\ic_launcher.png" 144
New-SimpleIcon "$base\mipmap-xxxhdpi\ic_launcher.png" 192

Write-Host "Icons created"
