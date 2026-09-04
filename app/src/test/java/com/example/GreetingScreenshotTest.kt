package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.data.model.Screening
import com.example.data.model.WatchlistItem
import com.example.ui.screens.WatchlistItemCard
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.WatchlistItemWithScreening
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun greeting_screenshot() {
    val sampleItem = WatchlistItemWithScreening(
      item = WatchlistItem(
        id = "test_1",
        title = "The Substance",
        year = 2024,
        director = "Coralie Fargeat",
        source = "IMDb",
        imdbRating = 7.4f
      ),
      nextScreening = Screening(
        id = "sc_1",
        watchlistMovieId = "test_1",
        movieTitle = "The Substance",
        cinemaId = "bio_capitol",
        cinemaName = "Bio Capitol",
        auditorium = "Salong 1 (Bistro)",
        screeningTime = System.currentTimeMillis() + 86400000L,
        formattedDateTime = "Tomorrow • 19:30",
        formatTag = "Bistro Dinner & Wine",
        bookingUrl = "https://www.capitolbio.se"
      )
    )

    composeTestRule.setContent {
      MyApplicationTheme {
        WatchlistItemCard(
          itemWithScreening = sampleItem,
          onBookClick = {},
          onDeleteClick = {}
        )
      }
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
  }
}

