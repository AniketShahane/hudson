#!/usr/bin/env python3
"""Checks that commonly used symbols are imported (or same-package) in the given Kotlin files.

Compose code cannot be compiled without the Android toolchain, and on a CI-only project most
red builds are a missing import. This catches them in a second: each entry maps a pattern that
uses a symbol to the import it needs. Run it from the package's source root:

    python3 sym_check.py ui/detail/FlightDetailScreen.kt notify/Notifier.kt

Add an entry whenever CI fails on an unresolved reference, and add the project's own
frequently used helpers at the bottom (the app.aloft.* lines are from the Wingtip app).
Known false positives: Rect, Size, .height( and TileMode when it is android.graphics.Shader.TileMode.
"""
import re, sys
SYMS = {
 r"\.drawBehind\b": "androidx.compose.ui.draw.drawBehind",
 r"\.drawWithCache\b": "androidx.compose.ui.draw.drawWithCache",
 r"\.drawWithContent\b": "androidx.compose.ui.draw.drawWithContent",
 r"\.fillMaxHeight\(": "androidx.compose.foundation.layout.fillMaxHeight",
 r"\.offset\(": "androidx.compose.foundation.layout.offset",
 r"\.requiredSize\(": "androidx.compose.foundation.layout.requiredSize",
 r"\.heightIn\(": "androidx.compose.foundation.layout.heightIn",
 r"\.navigationBarsPadding\(": "androidx.compose.foundation.layout.navigationBarsPadding",
 r"\.graphicsLayer\b": "androidx.compose.ui.graphics.graphicsLayer",
 r"\.border\(": "androidx.compose.foundation.border",
 r"\.shadow\(": "androidx.compose.ui.draw.shadow",
 r"\bOffset\(|\bOffset\.": "androidx.compose.ui.geometry.Offset",
 r"\bSize\(": "androidx.compose.ui.geometry.Size",
 r"\bRect\(": "Rect",
 r"\bPath\(\)": "androidx.compose.ui.graphics.Path",
 r"\bStroke\(": "androidx.compose.ui.graphics.drawscope.Stroke",
 r"\bStrokeCap\.": "androidx.compose.ui.graphics.StrokeCap",
 r"\bStrokeJoin\.": "androidx.compose.ui.graphics.StrokeJoin",
 r"\bCanvas\(": "Canvas",
 r"(?<![\w.])translate\(": "androidx.compose.ui.graphics.drawscope.translate",
 r"(?<![\w.])rotate\(": "androidx.compose.ui.graphics.drawscope.rotate",
 r"(?<![\w.])scale\((?!\d)": "androidx.compose.ui.graphics.drawscope.scale",
 r"(?<![\w.])clipPath\(": "androidx.compose.ui.graphics.drawscope.clipPath",
 r"(?<![\w.])clipRect\(": "androidx.compose.ui.graphics.drawscope.clipRect",
 r"(?<![\w.])withTransform\(": "androidx.compose.ui.graphics.drawscope.withTransform",
 r"\bdrawIntoCanvas\b": "androidx.compose.ui.graphics.drawscope.drawIntoCanvas",
 r"\.nativeCanvas\b": "androidx.compose.ui.graphics.nativeCanvas",
 r"\.toArgb\(": "androidx.compose.ui.graphics.toArgb",
 r"\.luminance\(": "androidx.compose.ui.graphics.luminance",
 r"\bPathParser\(": "androidx.compose.ui.graphics.vector.PathParser",
 r"\bImageVector\b": "androidx.compose.ui.graphics.vector.ImageVector",
 r"\bmutableFloatStateOf\(": "androidx.compose.runtime.mutableFloatStateOf",
 r"\bmutableIntStateOf\(": "androidx.compose.runtime.mutableIntStateOf",
 r"\bwithFrameNanos\b": "androidx.compose.runtime.withFrameNanos",
 r"\brememberSaveable\b": "androidx.compose.runtime.saveable.rememberSaveable",
 r"\bFontWeight\.": "androidx.compose.ui.text.font.FontWeight",
 r"\bImage\(": "androidx.compose.foundation.Image",
 r"\bpainterResource\(": "androidx.compose.ui.res.painterResource",
 r"\bLocalContext\b": "androidx.compose.ui.platform.LocalContext",
 r"\bLocalDensity\b": "androidx.compose.ui.platform.LocalDensity",
 r"\bContentScale\.": "androidx.compose.ui.layout.ContentScale",
 r"\bBoxWithConstraints\(": "androidx.compose.foundation.layout.BoxWithConstraints",
 r"\bAnimatedVisibility\(": "androidx.compose.animation.AnimatedVisibility",
 r"\bCrossfade\(": "androidx.compose.animation.Crossfade",
 r"\bPathOperation\.": "androidx.compose.ui.graphics.PathOperation",
 r"\bTileMode\.": "androidx.compose.ui.graphics.TileMode",
 r"\bDrawScope\b": "androidx.compose.ui.graphics.drawscope.DrawScope",

 r"\bWindowInsets\b": "androidx.compose.foundation.layout.WindowInsets",
 r"WindowInsets\.statusBars": "androidx.compose.foundation.layout.statusBars",
 r"\.asPaddingValues\(": "androidx.compose.foundation.layout.asPaddingValues",
 r"\bIconButton\(": "androidx.compose.material3.IconButton",
 r"\bTimes\.": "app.aloft.core.time.Times",
 r"\.tabular\(\)": "app.aloft.ui.theme.tabular",
 r"\.toShape\(\)": "androidx.compose.material3.toShape",
 r"\bFormat\.": "app.aloft.core.util.Format",
 r"\bSpectrum\.": "app.aloft.ui.theme.Spectrum",
 r"\bBrush\.": "androidx.compose.ui.graphics.Brush",
 r"\bCountries\.": "app.aloft.data.ref.Countries",
 r"\bAloftTheme\.": "app.aloft.ui.theme.AloftTheme",
 r"\bAloftIcons\.": "app.aloft.ui.icons.AloftIcons",
 r"\bMaterialShapes\.": "androidx.compose.material3.MaterialShapes",
 r"\bCircleShape\b": "androidx.compose.foundation.shape.CircleShape",
 r"\bRoundedCornerShape\(": "androidx.compose.foundation.shape.RoundedCornerShape",
 r"\.clip\(": "androidx.compose.ui.draw.clip",
 r"\.background\(": "androidx.compose.foundation.background",
 r"\.clickable\b": "androidx.compose.foundation.clickable",
 r"\.alpha\(": "androidx.compose.ui.draw.alpha",
 r"\bColor\.|\bColor\(": "androidx.compose.ui.graphics.Color",
 r"\blerp\(": "lerp",
 r"\bShadow\(": "androidx.compose.ui.graphics.Shadow",
 r"\bLazyRow\(": "androidx.compose.foundation.lazy.LazyRow",
 r"\bitemsIndexed\(": "androidx.compose.foundation.lazy.itemsIndexed",
 r"\bSurface\(": "androidx.compose.material3.Surface",
 r"\bIcon\(": "androidx.compose.material3.Icon",
 r"\bText\(": "androidx.compose.material3.Text",
 r"\bSpacer\(": "androidx.compose.foundation.layout.Spacer",
 r"\bColumn\(": "androidx.compose.foundation.layout.Column",
 r"\bRow\(": "androidx.compose.foundation.layout.Row",
 r"\bBox\(": "androidx.compose.foundation.layout.Box",
 r"\.fillMaxSize\(": "androidx.compose.foundation.layout.fillMaxSize",
 r"\.fillMaxWidth\(": "androidx.compose.foundation.layout.fillMaxWidth",
 r"\.height\(": "androidx.compose.foundation.layout.height",
 r"\.width\(": "androidx.compose.foundation.layout.width",
 r"\.widthIn\(": "androidx.compose.foundation.layout.widthIn",
 r"\.size\(": "androidx.compose.foundation.layout.size",
 r"\.padding\(": "androidx.compose.foundation.layout.padding",
 r"\.aspectRatio\(": "androidx.compose.foundation.layout.aspectRatio",
 r"\.statusBarsPadding\(": "androidx.compose.foundation.layout.statusBarsPadding",
 r"\bMaterialTheme\.": "androidx.compose.material3.MaterialTheme",
 r"\bAlignment\.": "androidx.compose.ui.Alignment",
 r"\bModifier\b": "androidx.compose.ui.Modifier",
 r"\bTextOverflow\.": "androidx.compose.ui.text.style.TextOverflow",
 r"\bTextAlign\.": "androidx.compose.ui.text.style.TextAlign",
 r"\d\.dp\b|\)\.dp\b": "androidx.compose.ui.unit.dp",
 r"\d\.sp\b|\)\.sp\b": "androidx.compose.ui.unit.sp",
 r"\bremember\(|\bremember \{": "androidx.compose.runtime.remember",
 r"\bLaunchedEffect\(": "androidx.compose.runtime.LaunchedEffect",
 r"\bmutableStateOf\(": "androidx.compose.runtime.mutableStateOf",
 r" by remember| by rememberSaveable| by animate": "androidx.compose.runtime.getValue",
 r"\bAnimatable\(": "androidx.compose.animation.core.Animatable",
 r"\bspring\(": "androidx.compose.animation.core.spring",
 r"\bArrangement\.": "androidx.compose.foundation.layout.Arrangement",
 r"\bPhotoImage\(": "app.aloft.ui.components.PhotoImage",
 r"\brememberPlacePhoto\(": "app.aloft.ui.components.rememberPlacePhoto",
 r"\bphotoTint\(": "app.aloft.ui.components.photoTint",
 r"\bAirlineBrand\.": "app.aloft.ui.components.AirlineBrand",
 r"\bPostcard\(": "app.aloft.ui.components.Postcard",
 r"\bkoinInject<": "org.koin.compose.koinInject",
 r"\bkoinViewModel\b": "org.koin.compose.viewmodel.koinViewModel",
 r"\bBadgeTier\.": "app.aloft.domain.BadgeTier",
 r"\bBriefing\.": "app.aloft.domain.Briefing",
}
bad = 0
for path in sys.argv[1:]:
    src = open(path, encoding="utf-8").read()
    pkg = re.search(r"^package (.+)$", src, re.M).group(1)
    imports = set(re.findall(r"^import (\S+)", src, re.M))
    body = re.sub(r"^import .*$", "", src, flags=re.M)
    body = re.sub(r"//.*$", "", body, flags=re.M)
    for pat, imp in SYMS.items():
        if not re.search(pat, body):
            continue
        if imp == "lerp":
            ok = any(i.endswith(".lerp") for i in imports)
        else:
            name = imp.rsplit(".", 1)[-1]
            ok = imp in imports or imp.rsplit(".", 1)[0] == pkg or any(i.endswith("." + name) for i in imports) or re.search(r"\b(fun|val|class|object) (\w+\.)?" + re.escape(name) + r"\b", body)
        if not ok:
            bad += 1
            print(f"{path}: missing import {imp}")
print("missing:", bad)
