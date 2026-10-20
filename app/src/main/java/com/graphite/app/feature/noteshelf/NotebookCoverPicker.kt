package com.graphite.app.feature.noteshelf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.graphite.app.data.model.NotebookCover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// Pre-defined cover collections matching PDF Pages 1-8 and user screenshots
object CoverCatalog {
    val PlainColors = listOf(
        NotebookCover(category = "PLAIN", colorHex = 0xFFD32F2F, name = "Crimson"),
        NotebookCover(category = "PLAIN", colorHex = 0xFFFBC02D, name = "Mustard"),
        NotebookCover(category = "PLAIN", colorHex = 0xFF45A5B5, name = "Teal"),
        NotebookCover(category = "PLAIN", colorHex = 0xFFFFAB91, name = "Peach"),
        NotebookCover(category = "PLAIN", colorHex = 0xFF00796B, name = "Emerald"),
        NotebookCover(category = "PLAIN", colorHex = 0xFF7E57C2, name = "Purple"),
        NotebookCover(category = "PLAIN", colorHex = 0xFFF5F2EB, name = "Ivory"),
        NotebookCover(category = "PLAIN", colorHex = 0xFF2B2B2B, name = "Obsidian")
    )

    // Sized and colored exactly like user's Band screenshot
    val BandCovers = listOf(
        NotebookCover(category = "BAND", colorHex = 0xFFD87080, bandColorHex = 0xFF2A5559, name = "Rose Teal Band"),
        NotebookCover(category = "BAND", colorHex = 0xFFE8D279, bandColorHex = 0xFF353D32, name = "Butter Slate Band"),
        NotebookCover(category = "BAND", colorHex = 0xFF7DBFA5, bandColorHex = 0xFFD25D6B, name = "Sage Coral Band"),
        NotebookCover(category = "BAND", colorHex = 0xFF985068, bandColorHex = 0xFF58614E, name = "Berry Khaki Band"),
        NotebookCover(category = "BAND", colorHex = 0xFFDE7A46, bandColorHex = 0xFFF0E5D0, name = "Ochre Cream Band"),
        NotebookCover(category = "BAND", colorHex = 0xFFD4917C, bandColorHex = 0xFF1976D2, name = "Peach Blue Band"),
        NotebookCover(category = "BAND", colorHex = 0xFFA27E84, bandColorHex = 0xFFE0C4C8, name = "Mauve Rose Band"),
        NotebookCover(category = "BAND", colorHex = 0xFFB0BEC5, bandColorHex = 0xFF37474F, name = "Slate Dark Band")
    )

    val PaletteCovers = listOf(
        NotebookCover(
            category = "PALETTE",
            colorHex = 0xFF2A5055,
            stripeColorHexes = listOf(0xFF2A5055, 0xFFE0E0E0, 0xFFE57373),
            name = "Nordic Tri-Tone"
        ),
        NotebookCover(
            category = "PALETTE",
            colorHex = 0xFFFFF8E7,
            stripeColorHexes = listOf(0xFFFFF8E7, 0xFFE0B2B2, 0xFF9E3D48),
            name = "Cherry Cream"
        ),
        NotebookCover(
            category = "PALETTE",
            colorHex = 0xFFDE6B48,
            stripeColorHexes = listOf(0xFFDE6B48, 0xFFF4A261, 0xFF264653),
            name = "Terracotta Dunes"
        ),
        NotebookCover(
            category = "PALETTE",
            colorHex = 0xFFE8D7F1,
            stripeColorHexes = listOf(0xFFE8D7F1, 0xFFD3BCC0, 0xFF432851),
            name = "Lavender Slate"
        )
    )

    val CompositionCovers = listOf(
        NotebookCover(category = "COMPOSITION", colorHex = 0xFF2B2B2B, patternType = "speckle", name = "Charcoal Composition"),
        NotebookCover(category = "COMPOSITION", colorHex = 0xFFD81B60, patternType = "speckle", name = "Berry Composition"),
        NotebookCover(category = "COMPOSITION", colorHex = 0xFF1E88E5, patternType = "speckle", name = "Sapphire Composition"),
        NotebookCover(category = "COMPOSITION", colorHex = 0xFF43A047, patternType = "speckle", name = "Jade Composition"),
        NotebookCover(category = "COMPOSITION", colorHex = 0xFFFFB300, patternType = "speckle", name = "Golden Composition")
    )

    // Replaced person silhouette with authentic organic curves and wavy contour art
    val AbstractCovers = listOf(
        NotebookCover(category = "ABSTRACT", colorHex = 0xFFC7E2E4, patternType = "waves", name = "Ocean Swell"),
        NotebookCover(category = "ABSTRACT", colorHex = 0xFFF5E6CC, patternType = "curves", name = "Boho Dunes"),
        NotebookCover(category = "ABSTRACT", colorHex = 0xFFD1E8E2, patternType = "contour", name = "Sage Topo"),
        NotebookCover(category = "ABSTRACT", colorHex = 0xFFF3C5B5, patternType = "fluid", name = "Terracotta Flow"),
        NotebookCover(category = "ABSTRACT", colorHex = 0xFFE1BEE7, patternType = "waves", name = "Lavender Drift"),
        NotebookCover(category = "ABSTRACT", colorHex = 0xFFFFF3E0, patternType = "curves", name = "Sunlight Ripple")
    )

    // Replaced person silhouette with genuine 70s geometric Bauhaus & Memphis patterns
    val RetroCovers = listOf(
        NotebookCover(category = "RETRO", colorHex = 0xFFECC880, patternType = "retro_bauhaus", name = "Bauhaus Block"),
        NotebookCover(category = "RETRO", colorHex = 0xFFDFC5FE, patternType = "retro_stripes", name = "70s Groove"),
        NotebookCover(category = "RETRO", colorHex = 0xFF90CAF9, patternType = "retro_memphis", name = "Memphis Pastel"),
        NotebookCover(category = "RETRO", colorHex = 0xFFFFAB91, patternType = "retro_circles", name = "Mod Concentric"),
        NotebookCover(category = "RETRO", colorHex = 0xFFCE93D8, patternType = "retro_stripes", name = "Vintage Ribbon")
    )

    // Comprehensive curated high-res covers matching PDF Pages 1-7 (Nature, Mountains, Space, Aesthetic, Minimalist, Animals) - all uniquely deduplicated
    val UnsplashCovers = listOf(
        // Page 1: Neon, Coastline, City, Misty Mountain, Forest, Temple, Pagoda, Winter, Dolomites
        NotebookCover(category = "CUSTOM", colorHex = 0xFF12131A, name = "Neon Light Wave", customImageUri = "https://images.unsplash.com/photo-1550684848-fac1c5b4e853?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFFC86446, name = "Cinque Terre Sunset", customImageUri = "https://images.unsplash.com/photo-1516483638261-f4dbaf036963?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF101B2B, name = "Futuristic City Canyon", customImageUri = "https://images.unsplash.com/photo-1519501025264-65ba15a82390?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF4A6572, name = "Misty Dawn Lake", customImageUri = "https://images.unsplash.com/photo-1506744038136-46273834b3fb?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF354E35, name = "Sunbeam Forest Road", customImageUri = "https://images.unsplash.com/photo-1448375240586-882707db888b?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF2A6F68, name = "Palm Trees Water Mirror", customImageUri = "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF422D3E, name = "Yasaka Pagoda Kyoto", customImageUri = "https://images.unsplash.com/photo-1493976040374-85c8e12f0c0e?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFFE0E0E0, name = "Lone Winter Tree", customImageUri = "https://images.unsplash.com/photo-1517411032315-54ef2cb783bb?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFFB33927, name = "Red Dolomite Peak", customImageUri = "https://images.unsplash.com/photo-1464822759023-fed622ff2c3b?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF141A29, name = "Indigo Crescent Moon", customImageUri = "https://images.unsplash.com/photo-1532693322450-2cb5c511067d?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF5D6D5E, name = "Glenfinnan Viaduct", customImageUri = "https://images.unsplash.com/photo-1508873535684-277a3cbcc4e8?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF657A58, name = "Rolling Green Highlands", customImageUri = "https://images.unsplash.com/photo-1500534314209-a25ddb2bd429?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF234E52, name = "Mountain Islet Lake", customImageUri = "https://images.unsplash.com/photo-1439853941329-a93ac1617a20?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF1E5B70, name = "Alpine Lake Moraine", customImageUri = "https://images.unsplash.com/photo-1536782376847-5c9d14d97cc0?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF1B4D3E, name = "Tropical Fern Canopy", customImageUri = "https://images.unsplash.com/photo-1518531933037-91b2f5f229cc?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF3B4856, name = "Moody Blue Ridge", customImageUri = "https://images.unsplash.com/photo-1486870591958-9b9d0d1dda99?auto=format&fit=crop&w=800&q=80"),

        // Page 2: Turquoise River, Coffee Aesthetic, Rain City, Mossy Woods, Water Ripple, Flowers, Tiger
        NotebookCover(category = "CUSTOM", colorHex = 0xFF1F6064, name = "Turquoise River Rapids", customImageUri = "https://images.unsplash.com/photo-1433086966358-54859d0ed716?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF6E5848, name = "Vintage Coffee Art", customImageUri = "https://images.unsplash.com/photo-1495474472287-4d71bcdd2085?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF232533, name = "Rainy Cyberpunk Street", customImageUri = "https://images.unsplash.com/photo-1514565131-fce0801e5785?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF2E432A, name = "Ancient Moss Redwood", customImageUri = "https://images.unsplash.com/photo-1511497584788-87676104235f?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF242226, name = "Crimson Smoke Silhouette", customImageUri = "https://images.unsplash.com/photo-1509198397868-475647b2a1e5?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF3A8891, name = "Crystal Water Ripple", customImageUri = "https://images.unsplash.com/photo-1518837695005-2083093ee35b?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFFE08D6A, name = "Macro Peach Flora", customImageUri = "https://images.unsplash.com/photo-1490750967868-88aa4486c946?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF245084, name = "Flowing Cobalt Silk", customImageUri = "https://images.unsplash.com/photo-1579783900882-c0d3dad7b119?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF140D26, name = "Carina Deep Nebula", customImageUri = "https://images.unsplash.com/photo-1451187580459-43490279c0fa?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF0B1426, name = "Radiant Full Moon", customImageUri = "https://images.unsplash.com/photo-1522030299830-16b8d3d049fe?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFFDDAA22, name = "Golden Sunflowers", customImageUri = "https://images.unsplash.com/photo-1597848212624-a19eb35e2651?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFFE5B0BA, name = "Pastel Pink Blossom", customImageUri = "https://images.unsplash.com/photo-1508615039623-a25605d2b022?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFFB33A20, name = "Neon Cyberpunk Tiger", customImageUri = "https://images.unsplash.com/photo-1561731216-c3a4d99437d5?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF4D4D54, name = "Retro Film Camera", customImageUri = "https://images.unsplash.com/photo-1516035069371-29a1b244cc32?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF769ECB, name = "Pastel Rainbow Horizon", customImageUri = "https://images.unsplash.com/photo-1534447677768-be436bb09401?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF1D3B4A, name = "Deep Ocean Coral Swirl", customImageUri = "https://images.unsplash.com/photo-1544551763-46a013bb70d5?auto=format&fit=crop&w=800&q=80"),

        // Page 3: Lion, Pastel Ridge, Clouds, Minimal Rose, White Jasmine, Peak Wanderer, Golden Gate, Mandala
        NotebookCover(category = "CUSTOM", colorHex = 0xFF8B6B38, name = "Golden Lion Resting", customImageUri = "https://images.unsplash.com/photo-1534188753412-3e26d0d618d6?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFFD8927A, name = "Layered Pastel Sunrise", customImageUri = "https://images.unsplash.com/photo-1495616811223-4d98c6e9c869?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFFB87842, name = "Celestial Sunset Sky", customImageUri = "https://images.unsplash.com/photo-1472214103451-9374bd1c798e?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF9E2A2B, name = "Minimal Red Rose", customImageUri = "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF2A362B, name = "White Jasmine Blooms", customImageUri = "https://images.unsplash.com/photo-1526047932273-341f2a7631f9?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF9C6A48, name = "Peak Wanderer Sunset", customImageUri = "https://images.unsplash.com/photo-1469854523086-cc02fe5d8800?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFFB25E5E, name = "Golden Gate Pink Fog", customImageUri = "https://images.unsplash.com/photo-1501594907352-04cda38ebc29?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF14746F, name = "Emerald Coast Lagoon", customImageUri = "https://images.unsplash.com/photo-1505118380757-91f5f5632de0?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFFC97A7E, name = "Pink Alpenglow Summit", customImageUri = "https://images.unsplash.com/photo-1483728642387-6c3bdd6c93e5?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF1B263B, name = "Starlit Mountain Ridge", customImageUri = "https://images.unsplash.com/photo-1519681393784-d120267933ba?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFFD88C9A, name = "Sunset Palm Silhouettes", customImageUri = "https://images.unsplash.com/photo-1512100356356-de1b84283e18?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFFE07A5F, name = "Mandala Bohemian Art", customImageUri = "https://images.unsplash.com/photo-1579783902614-a3fb3927b675?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF2C3539, name = "Dark Storm Horizon", customImageUri = "https://images.unsplash.com/photo-1465056836041-7f43ac27dcb5?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF9E4770, name = "Neon Life Is Beautiful", customImageUri = "https://images.unsplash.com/photo-1563089145-599997674d42?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF181818, name = "Midnight Geometric Waves", customImageUri = "https://images.unsplash.com/photo-1541701494587-cb58502866ab?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFFCC7A3D, name = "Billowing Golden Nimbus", customImageUri = "https://images.unsplash.com/photo-1513002749550-c59d786b8e6c?auto=format&fit=crop&w=800&q=80"),

        // Page 4: Boathouse Lake, Turquoise Louise, Lone Tree Galaxy, Skyline Night, Antelope Canyon
        NotebookCover(category = "CUSTOM", colorHex = 0xFF244855, name = "Lake Boathouse Mist", customImageUri = "https://images.unsplash.com/photo-1470071459604-3b5ec3a7fe05?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF186F65, name = "Glacial Lake Louise", customImageUri = "https://images.unsplash.com/photo-1476514525535-07fb3b4ae5f1?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF161E38, name = "Lone Tree Milky Way", customImageUri = "https://images.unsplash.com/photo-1419242902214-272b3f66ee7a?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF4A2545, name = "Twilight Purple Horizon", customImageUri = "https://images.unsplash.com/photo-1470770841072-f978cf4d019e?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF0D1B2A, name = "Metropolis Skyline Night", customImageUri = "https://images.unsplash.com/photo-1477959858617-67f30bc75b82?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF301934, name = "Electric Purple Lightning", customImageUri = "https://images.unsplash.com/photo-1516912481808-3406841bd33c?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF6A4C93, name = "Velvet Purple Ridges", customImageUri = "https://images.unsplash.com/photo-1509316975850-ff9c5deb0cd9?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF1B4332, name = "Emerald Rainforest Coast", customImageUri = "https://images.unsplash.com/photo-1501785888041-af3ef285b470?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF2B3A67, name = "Deep Indigo Wave Curve", customImageUri = "https://images.unsplash.com/photo-1498050108023-c5249f4df085?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF335C67, name = "Pine Lake Reflection", customImageUri = "https://images.unsplash.com/photo-1502082553048-f009c37129b9?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF14213D, name = "Earth Space Atmosphere", customImageUri = "https://images.unsplash.com/photo-1614728894747-a83421e2b9c9?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFFD97706, name = "Scarlet Macaw Perch", customImageUri = "https://images.unsplash.com/photo-1552728089-57bdde30beb3?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF2563EB, name = "Cappadocia Hot Air Balloons", customImageUri = "https://images.unsplash.com/photo-1507608869274-d3177c8bb4c7?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF15803D, name = "Alpine Meadow Cow", customImageUri = "https://images.unsplash.com/photo-1527153857715-3908f2ae5e81?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFF78350F, name = "Machu Picchu Ancient Ridge", customImageUri = "https://images.unsplash.com/photo-1509299349698-dd22323b5963?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFFD97706, name = "Cute Golden Puppy", customImageUri = "https://images.unsplash.com/photo-1543466835-00a7907e9de1?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFFB45309, name = "Yosemite Valley Sunbeam", customImageUri = "https://images.unsplash.com/photo-1426604966848-d7adac402bff?auto=format&fit=crop&w=800&q=80"),
        NotebookCover(category = "CUSTOM", colorHex = 0xFFFDA4AF, name = "White Cherry Blossoms", customImageUri = "https://images.unsplash.com/photo-1522383225653-ed111181a951?auto=format&fit=crop&w=800&q=80")
    )
}

/**
 * Handles automatic cropping and sizing of imported gallery images to standard notebook cover ratio (3:4)
 */
object CoverImageProcessor {
    suspend fun processAndSaveCoverImage(context: Context, sourceUri: Uri): Uri? = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(sourceUri) ?: return@withContext null
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (originalBitmap == null) return@withContext null

            val srcW = originalBitmap.width
            val srcH = originalBitmap.height
            // Standard notebook cover aspect ratio: 3:4 (0.75f)
            val targetAspect = 3f / 4f
            val srcAspect = srcW.toFloat() / srcH.toFloat()

            val cropW: Int
            val cropH: Int
            val startX: Int
            val startY: Int

            if (srcAspect > targetAspect) {
                // Source is wider than 3:4 -> crop sides, keep height
                cropH = srcH
                cropW = (srcH * targetAspect).toInt().coerceAtMost(srcW)
                startX = ((srcW - cropW) / 2).coerceAtLeast(0)
                startY = 0
            } else {
                // Source is taller than 3:4 -> crop top & bottom, keep width
                cropW = srcW
                cropH = (srcW / targetAspect).toInt().coerceAtMost(srcH)
                startX = 0
                startY = ((srcH - cropH) / 2).coerceAtLeast(0)
            }

            val croppedBitmap = Bitmap.createBitmap(originalBitmap, startX, startY, cropW, cropH)

            // Normalize size up to 1200x1600 max for crisp quality without excess memory
            val maxW = 1200
            val maxH = 1600
            val scaledBitmap = if (cropW > maxW || cropH > maxH) {
                Bitmap.createScaledBitmap(croppedBitmap, maxW, maxH, true)
            } else {
                croppedBitmap
            }

            val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
            val coverFile = File(coversDir, "cover_${System.currentTimeMillis()}.jpg")
            FileOutputStream(coverFile).use { fos ->
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                fos.flush()
            }

            if (croppedBitmap != originalBitmap && !croppedBitmap.isRecycled) {
                croppedBitmap.recycle()
            }
            if (scaledBitmap != croppedBitmap && !scaledBitmap.isRecycled) {
                scaledBitmap.recycle()
            }
            if (!originalBitmap.isRecycled) {
                originalBitmap.recycle()
            }

            Uri.fromFile(coverFile)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

@Composable
fun NotebookCoverView(
    cover: NotebookCover,
    modifier: Modifier = Modifier,
    isThumbnail: Boolean = false
) {
    val baseColor = Color(cover.colorHex)

    Card(
        modifier = modifier
            .shadow(if (isThumbnail) 4.dp else 10.dp, RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp, topStart = 2.dp, bottomStart = 2.dp)),
        shape = RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp, topStart = 2.dp, bottomStart = 2.dp),
        colors = CardDefaults.cardColors(containerColor = baseColor)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            // Custom Photographic Wallpaper Cover (Unsplash / Photo)
            if (!cover.customImageUri.isNullOrBlank()) {
                SubcomposeAsyncImage(
                    model = cover.customImageUri,
                    contentDescription = cover.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = {
                        Box(modifier = Modifier.fillMaxSize().background(baseColor))
                    },
                    error = {
                        Box(modifier = Modifier.fillMaxSize().background(baseColor))
                    }
                )
            } else {
                // Category-specific decorations
                when (cover.category) {
                    "NO_COVER" -> {
                        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF232323)))
                    }
                    "BAND" -> {
                        val bandCol = cover.bandColorHex?.let { Color(it) } ?: Color(0xFF333333)
                        val bandW = if (isThumbnail) 4.dp else 11.dp
                        val bandPaddingEnd = if (isThumbnail) 8.dp else 22.dp

                        // Slim Moleskine vertical ribbon near right edge
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = bandPaddingEnd)
                                .fillMaxHeight()
                                .width(bandW)
                                .background(bandCol)
                        ) {
                            // Left edge drop shadow
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .fillMaxHeight()
                                    .width(if (isThumbnail) 0.8.dp else 1.5.dp)
                                    .background(Color.Black.copy(alpha = 0.35f))
                            )
                            // Right edge drop shadow
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight()
                                    .width(if (isThumbnail) 0.8.dp else 1.5.dp)
                                    .background(Color.Black.copy(alpha = 0.35f))
                            )
                            // Center subtle ribbon highlight
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .fillMaxHeight()
                                    .width(if (isThumbnail) 1.dp else 2.dp)
                                    .background(Color.White.copy(alpha = 0.15f))
                            )
                        }
                    }
                    "PALETTE" -> {
                        cover.stripeColorHexes?.let { stripes ->
                            Column(modifier = Modifier.fillMaxSize()) {
                                stripes.forEach { sCol ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                            .background(Color(sCol))
                                    )
                                }
                            }
                        }
                    }
                    "COMPOSITION" -> {
                        // Central white rectangular label box matching classic composition notebooks
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth(0.68f)
                                .height(if (isThumbnail) 32.dp else 60.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White)
                                .border(1.5.dp, Color(0xFF1E1E1E), RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                modifier = Modifier.padding(4.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(modifier = Modifier.fillMaxWidth(0.85f).height(1.dp).background(Color.LightGray))
                                Spacer(modifier = Modifier.height(if (isThumbnail) 5.dp else 10.dp))
                                Box(modifier = Modifier.fillMaxWidth(0.85f).height(1.dp).background(Color.LightGray))
                            }
                        }
                    }
                    "ABSTRACT" -> {
                        // Genuine fluid organic waves and geometric curves - NO contact/user silhouette
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height

                            val wavePath1 = Path().apply {
                                moveTo(0f, h * 0.35f)
                                cubicTo(w * 0.3f, h * 0.2f, w * 0.6f, h * 0.5f, w, h * 0.3f)
                                lineTo(w, h)
                                lineTo(0f, h)
                                close()
                            }
                            drawPath(wavePath1, color = Color.White.copy(alpha = 0.15f))

                            val wavePath2 = Path().apply {
                                moveTo(0f, h * 0.55f)
                                cubicTo(w * 0.4f, h * 0.65f, w * 0.7f, h * 0.45f, w, h * 0.6f)
                                lineTo(w, h)
                                lineTo(0f, h)
                                close()
                            }
                            drawPath(wavePath2, color = Color.White.copy(alpha = 0.22f))

                            // Organic subtle accent contour
                            drawCircle(
                                color = Color.White.copy(alpha = 0.12f),
                                radius = w * 0.32f,
                                center = androidx.compose.ui.geometry.Offset(w * 0.85f, h * 0.18f)
                            )
                        }
                    }
                    "RETRO" -> {
                        // 70s Bauhaus color block / diagonal ribbon pattern - NO contact/user silhouette
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height

                            // Bauhaus quarter circle in top right
                            drawArc(
                                color = Color.White.copy(alpha = 0.22f),
                                startAngle = 90f,
                                sweepAngle = 90f,
                                useCenter = true,
                                topLeft = androidx.compose.ui.geometry.Offset(w * 0.35f, -h * 0.1f),
                                size = androidx.compose.ui.geometry.Size(w * 0.8f, h * 0.5f)
                            )

                            // Retro horizontal band duo
                            drawRect(
                                color = Color.Black.copy(alpha = 0.12f),
                                topLeft = androidx.compose.ui.geometry.Offset(0f, h * 0.62f),
                                size = androidx.compose.ui.geometry.Size(w, h * 0.08f)
                            )
                            drawRect(
                                color = Color.White.copy(alpha = 0.25f),
                                topLeft = androidx.compose.ui.geometry.Offset(0f, h * 0.72f),
                                size = androidx.compose.ui.geometry.Size(w, h * 0.08f)
                            )
                        }
                    }
                }
            }

            // Left book spine gradient & stitch indentation line
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(if (isThumbnail) 8.dp else 18.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.28f),
                                Color.Black.copy(alpha = 0.08f),
                                Color.Transparent
                            )
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(start = if (isThumbnail) 6.dp else 16.dp)
                    .width(1.dp)
                    .background(Color.Black.copy(alpha = 0.15f))
            )
        }
    }
}

@Composable
fun FullCoverPickerSheet(
    selectedCover: NotebookCover,
    onSelectCover: (NotebookCover) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var activeCategoryIndex by remember { mutableIntStateOf(2) } // 0=No Cover, 1=Custom, 2=Plain, 3=Band, 4=Palette, 5=Composition, 6=Abstract, 7=Retro
    val categories = listOf("NO COVER", "CUSTOM", "PLAIN", "BAND", "PALETTE", "COMPOSITION", "ABSTRACT", "RETRO")
    var showUnsplashSubscreen by remember { mutableStateOf(false) }

    val galleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val processedFileUri = CoverImageProcessor.processAndSaveCoverImage(context, uri)
                if (processedFileUri != null) {
                    onSelectCover(
                        NotebookCover(
                            category = "CUSTOM",
                            colorHex = 0xFF2A2A2E,
                            name = "Imported Gallery Cover",
                            customImageUri = processedFileUri.toString()
                        )
                    )
                    Toast.makeText(context, "Cover image auto-fit to 3:4 aspect ratio!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Could not load image from gallery", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    if (showUnsplashSubscreen) {
        UnsplashPhotoPickerScreen(
            selectedCover = selectedCover,
            onSelectCover = {
                onSelectCover(it)
                showUnsplashSubscreen = false
            },
            onBack = { showUnsplashSubscreen = false }
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF19191B))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header: ✕ Choose Cover ... Done
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Choose Cover", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
                }
                Text(
                    text = "Done",
                    color = Color(0xFF00BCD4),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onDismiss() }.padding(8.dp)
                )
            }

            // Central large live preview card
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                NotebookCoverView(
                    cover = selectedCover,
                    modifier = Modifier
                        .fillMaxHeight(0.85f)
                        .aspectRatio(0.72f),
                    isThumbnail = false
                )
            }

            // Bottom drawer: Category selector tabs and items
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF222226))
                    .padding(top = 12.dp, bottom = 24.dp)
            ) {
                // Category tabs
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(categories.indices.toList()) { idx ->
                        val catName = categories[idx]
                        val isSelected = activeCategoryIndex == idx
                        Text(
                            text = catName,
                            color = if (isSelected) Color(0xFF00BCD4) else Color.Gray,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { activeCategoryIndex = idx }
                                .padding(vertical = 6.dp, horizontal = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Item options per category
                when (categories[activeCategoryIndex]) {
                    "NO COVER" -> {
                        Row(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(width = 65.dp, height = 90.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF2B2B2B))
                                    .border(if (selectedCover.category == "NO_COVER") 2.dp else 1.dp, if (selectedCover.category == "NO_COVER") Color(0xFF00BCD4) else Color.DarkGray, RoundedCornerShape(4.dp))
                                    .clickable { onSelectCover(NotebookCover(category = "NO_COVER", colorHex = 0xFF232323, name = "No Cover")) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("None", color = Color.Gray, fontSize = 11.sp)
                            }
                        }
                    }
                    "CUSTOM" -> {
                        // PDF Page 3: Unsplash, Camera, Photos
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            listOf(
                                Triple("Unsplash", Icons.Default.Filter, Color(0xFF5D4037)),
                                Triple("Camera", Icons.Default.PhotoCamera, Color(0xFF00838F)),
                                Triple("Photos", Icons.Default.Image, Color(0xFFC2185B))
                            ).forEach { (label, icon, col) ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            when (label) {
                                                "Unsplash" -> showUnsplashSubscreen = true
                                                "Photos", "Camera" -> galleryPickerLauncher.launch("image/*")
                                                else -> onSelectCover(NotebookCover(category = "CUSTOM", colorHex = col.value.toLong(), name = label))
                                            }
                                        }
                                        .padding(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(54.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(col.copy(alpha = 0.35f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(26.dp))
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(label, color = Color.White, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    else -> {
                        val itemsList = when (categories[activeCategoryIndex]) {
                            "PLAIN" -> CoverCatalog.PlainColors
                            "BAND" -> CoverCatalog.BandCovers
                            "PALETTE" -> CoverCatalog.PaletteCovers
                            "COMPOSITION" -> CoverCatalog.CompositionCovers
                            "ABSTRACT" -> CoverCatalog.AbstractCovers
                            "RETRO" -> CoverCatalog.RetroCovers
                            else -> CoverCatalog.PlainColors
                        }

                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(itemsList) { coverItem ->
                                val isChosen = selectedCover.colorHex == coverItem.colorHex && selectedCover.category == coverItem.category
                                Box(
                                    modifier = Modifier
                                        .size(width = 62.dp, height = 86.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .border(
                                            width = if (isChosen) 2.dp else 1.dp,
                                            color = if (isChosen) Color(0xFF00BCD4) else Color(0xFF3E3E44),
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .clickable { onSelectCover(coverItem) }
                                ) {
                                    NotebookCoverView(cover = coverItem, modifier = Modifier.fillMaxSize(), isThumbnail = true)
                                    if (isChosen) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color.Black.copy(alpha = 0.25f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UnsplashPhotoPickerScreen(
    selectedCover: NotebookCover,
    onSelectCover: (NotebookCover) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    val categories = listOf("All", "Aesthetic & Mood", "Nature & Mountains", "Lakes & Oceans", "Galaxy & Space", "Animals & Wildlife", "Minimalist & Art")

    val galleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val processedFileUri = CoverImageProcessor.processAndSaveCoverImage(context, uri)
                if (processedFileUri != null) {
                    onSelectCover(
                        NotebookCover(
                            category = "CUSTOM",
                            colorHex = 0xFF2A2A2E,
                            name = "Imported Gallery Cover",
                            customImageUri = processedFileUri.toString()
                        )
                    )
                    Toast.makeText(context, "Cover image auto-fit to 3:4 aspect ratio!", Toast.LENGTH_SHORT).show()
                    onBack()
                } else {
                    Toast.makeText(context, "Could not load image from gallery", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val allWallpapers = remember { CoverCatalog.UnsplashCovers.distinctBy { it.customImageUri ?: it.name } }
    val isUrlInput = searchQuery.trim().startsWith("http://", ignoreCase = true) || searchQuery.trim().startsWith("https://", ignoreCase = true)

    val categoryFiltered = remember(selectedCategory, allWallpapers) {
        if (selectedCategory == "All") allWallpapers
        else {
            val keywords = when (selectedCategory) {
                "Aesthetic & Mood" -> listOf("Aesthetic", "Neon", "Cyberpunk", "Vintage", "Coffee", "Rain", "Street", "Rainbow", "Life", "Silk", "Flow", "Retro", "Light")
                "Nature & Mountains" -> listOf("Mountain", "Peak", "Dolomite", "Highlands", "Forest", "Pass", "Yosemite", "Alps", "Machu", "Meadow", "Canyon", "Ridge", "Tree", "Fields", "Sunbeam")
                "Lakes & Oceans" -> listOf("Lake", "Moraine", "Louise", "Ocean", "Lagoon", "Bay", "Sea", "Wave", "Water", "Rapids", "Fjord", "Atoll", "Beach", "River", "Pond", "Coast")
                "Galaxy & Space" -> listOf("Milky Way", "Nebula", "Space", "Moon", "Stars", "Cosmos", "Orbit", "Planet", "Eclipse", "Atmosphere", "Night")
                "Animals & Wildlife" -> listOf("Lion", "Macaw", "Tiger", "Puppy", "Cow", "Koi", "Parrot", "Fauna")
                "Minimalist & Art" -> listOf("Minimal", "Rose", "Jasmine", "Blossom", "Sunflower", "Tree", "Mandala", "Viaduct", "Pagoda", "Camera", "Cherry")
                else -> emptyList()
            }
            allWallpapers.filter { item ->
                keywords.any { k -> item.name.contains(k, ignoreCase = true) }
            }
        }
    }

    val filtered = remember(searchQuery, categoryFiltered) {
        if (searchQuery.isBlank() || isUrlInput) categoryFiltered
        else {
            categoryFiltered.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF19191B))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header: ← Unsplash & Online Wallpapers ... [Gallery] Done
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Unsplash & Online Designs", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { galleryPickerLauncher.launch("image/*") },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00BCD4)),
                        border = BorderStroke(1.dp, Color(0xFF00BCD4).copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp).padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null, tint = Color(0xFF00BCD4), modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Gallery", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        text = "Done",
                        color = Color(0xFF00BCD4),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onBack() }.padding(8.dp)
                    )
                }
            }

            // Central large live preview card of currently selected cover
            Box(
                modifier = Modifier
                    .weight(0.50f)
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                NotebookCoverView(
                    cover = selectedCover,
                    modifier = Modifier
                        .fillMaxHeight(0.92f)
                        .aspectRatio(0.72f),
                    isThumbnail = false
                )
            }

            // Bottom Drawer: Search icon, Category filter chips + wallpaper thumbnail grid
            Column(
                modifier = Modifier
                    .weight(0.50f)
                    .fillMaxWidth()
                    .background(Color(0xFF222226))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Search field / Image URL input
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search designs or paste Pinterest / Google image URL...", color = Color.Gray, fontSize = 12.sp) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF00BCD4)) },
                    trailingIcon = {
                        if (isUrlInput) {
                            Button(
                                onClick = {
                                    onSelectCover(
                                        NotebookCover(
                                            category = "CUSTOM",
                                            colorHex = 0xFF2A2A2E,
                                            name = "Custom Web Design",
                                            customImageUri = searchQuery.trim()
                                        )
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BCD4)),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp).padding(end = 4.dp)
                            ) {
                                Text("Apply", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        } else if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color.Gray, modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00BCD4),
                        unfocusedBorderColor = Color(0xFF3E3E44),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Category chips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    items(categories) { cat ->
                        val isSelected = selectedCategory == cat
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) Color(0xFF00BCD4) else Color(0xFF323238))
                                .clickable { selectedCategory = cat }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = cat,
                                color = if (isSelected) Color.Black else Color.White,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Grid of Unsplash & curated wallpapers
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 72.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filtered) { item ->
                        val isChosen = selectedCover.customImageUri == item.customImageUri
                        Box(
                            modifier = Modifier
                                .size(width = 72.dp, height = 98.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(
                                    width = if (isChosen) 2.dp else 1.dp,
                                    color = if (isChosen) Color(0xFF00BCD4) else Color(0xFF333333),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable { onSelectCover(item) }
                        ) {
                            SubcomposeAsyncImage(
                                model = item.customImageUri,
                                contentDescription = item.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                                loading = {
                                    Box(modifier = Modifier.fillMaxSize().background(Color(item.colorHex)))
                                }
                            )

                            if (isChosen) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.35f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
