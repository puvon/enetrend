package io.github.puvon.enetrend.health

import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import io.github.puvon.enetrend.ui.DashboardChartProjector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import org.junit.Assert.*
import org.junit.Test

class DashboardLoaderTest {
    private val today = LocalDate.of(2026, 9, 14)
    private val zone = ZoneId.of("Asia/Tokyo")
    private class Source : HealthDataSource {
        override val requiredPermissions = setOf("read")
        var granted = requiredPermissions
        val ranges = mutableListOf<HealthDataRange>()
        val weightRanges = mutableListOf<HealthDataRange>()
        var onWeightRead: suspend (HealthDataRange) -> Unit = {}
        var read: (HealthDataRange) -> CalorieTotals = { CalorieTotals(2000.0, 2200.0) }
        var weights = emptyList<WeightMeasurement>()
        override fun availability() = HealthAvailability.AVAILABLE
        override suspend fun grantedPermissions() = granted
        override suspend fun readCalorieTotals(range: HealthDataRange): CalorieTotals {
            ranges.add(range)
            return read(range)
        }
        override suspend fun readWeightPage(range: HealthDataRange, pageToken: String?): WeightPage {
            weightRanges.add(range)
            onWeightRead(range)
            return WeightPage(weights.filter { it.time >= range.startTime && it.time < range.endTime }, null)
        }
    }

    private fun populatedSource() = Source().apply {
        weights = (0L..60L).map { offset ->
            WeightMeasurement("$offset", today.minusDays(offset).atTime(8, 0).atZone(zone).toInstant(), null,
                70.0 + offset / 10.0, "test", Instant.EPOCH)
        }
    }

    @Test fun midnightDuringReadDiscardsOldWindowAndUsesOneNewTimestamp() = runBlocking {
        var instant = Instant.parse("2026-12-31T14:59:59Z")
        val clock = object : Clock() {
            override fun getZone(): ZoneId = this@DashboardLoaderTest.zone
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant, zone)
            override fun instant() = instant
        }
        val source = Source().apply {
            onWeightRead = { instant = Instant.parse("2026-12-31T15:00:01Z") }
            read = { if (it.startDate.toString() == "2027-01-01") CalorieTotals(null, 10.0)
                else CalorieTotals(2000.0, 2200.0) }
        }
        val data = (DashboardLoader(HealthDataRepository(source)).loadCurrent(7,
            MovingAveragePeriod.SEVEN_DAYS, DashboardTimeSource(clock) { zone }) as DashboardState.Ready).data
        assertEquals(2, source.weightRanges.size)
        assertEquals("2027-01-01", data.today!!.date.toString())
        assertEquals(instant, data.today.readStartedAt)
        assertEquals(data.today.date.plusDays(1), data.balances.range.endDateExclusive)
        assertEquals(data.today.date, data.today.previousSevenDays.range.endDateExclusive)
        assertEquals(data.today.date.minusDays(7), data.today.previousSevenDays.range.startDate)
        assertEquals(10.0, data.today.calories.burnedKilocalories!!, 0.0)
    }

    @Test fun zoneChangeDuringReadRetriesAllWindowsEvenOnSameCalendarDay() = runBlocking {
        var currentZone = zone
        val instant = Instant.parse("2026-09-14T12:00:00Z")
        val source = Source().apply { onWeightRead = { currentZone = ZoneId.of("Europe/London") } }
        val data = (DashboardLoader(HealthDataRepository(source)).loadCurrent(7,
            MovingAveragePeriod.SEVEN_DAYS,
            DashboardTimeSource(Clock.fixed(instant, zone)) { currentZone }) as DashboardState.Ready).data
        assertEquals(2, source.weightRanges.size)
        assertEquals(currentZone, data.balances.range.zoneId)
        assertEquals(currentZone, data.today!!.previousSevenDays.range.zoneId)
        assertEquals(instant, data.today.readStartedAt)
    }

    @Test fun permissionRevocationAndRecoveryDoNotReuseEarlierCard() = runBlocking {
        val source = Source()
        val loader = DashboardLoader(HealthDataRepository(source))
        val time = DashboardTimeSource(Clock.fixed(today.atStartOfDay(zone).toInstant(), zone)) { zone }
        assertTrue(loader.loadCurrent(7, MovingAveragePeriod.SEVEN_DAYS, time) is DashboardState.Ready)
        source.granted = emptySet()
        assertTrue(loader.loadCurrent(7, MovingAveragePeriod.SEVEN_DAYS, time) is DashboardState.Failed)
        source.granted = source.requiredPermissions
        source.read = { CalorieTotals(null, 500.0) }
        val recovered = (loader.loadCurrent(7, MovingAveragePeriod.SEVEN_DAYS, time) as DashboardState.Ready).data
        assertNull(recovered.today!!.calories.intakeKilocalories)
        assertEquals(today.plusDays(1), recovered.balances.range.endDateExclusive)
        assertEquals(500.0, recovered.today.calorieSummary.intakeAllowanceKilocalories!!, 0.0)
    }

    @Test fun changingDisplayRangeAlignsAllSeriesAndReselectsBaseline() = runBlocking {
        val loader = DashboardLoader(HealthDataRepository(populatedSource()))
        for (length in listOf(30, 7, 14, 30)) {
            val data = (loader.load(today, zone, length, MovingAveragePeriod.SEVEN_DAYS) as DashboardState.Ready).data
            val chart = DashboardChartProjector.project(data)
            val first = today.minusDays(length.toLong() - 1)
            assertEquals(first, chart.range.startDate)
            assertEquals(today.plusDays(1), chart.range.endDateExclusive)
            assertEquals(first, chart.baseline?.date)
            assertEquals(70.0 + (length - 1) / 10.0, chart.baseline!!.kilograms, 1e-10)
            assertEquals(-200.0, chart.days.first().periodCumulative!!.kilocalories!!, 0.0)
            assertEquals(-200.0 * length, chart.days.last().periodCumulative!!.kilocalories!!, 0.0)
            assertEquals(length, chart.days.size)
            chart.days.forEach {
                assertEquals(it.date, it.daily!!.date)
                assertEquals(it.date, it.periodCumulative!!.date)
                assertEquals(it.date, it.weight!!.date)
            }
        }
    }

    @Test fun changingAverageOnlyKeepsBalancesAndBaselineButChangesAverage() = runBlocking {
        val loader = DashboardLoader(HealthDataRepository(populatedSource()))
        val short = (loader.load(today, zone, 7, MovingAveragePeriod.SEVEN_DAYS) as DashboardState.Ready).data
        val long = (loader.load(today, zone, 7, MovingAveragePeriod.THIRTY_DAYS) as DashboardState.Ready).data
        assertEquals(short.balances, long.balances)
        assertEquals(DashboardChartProjector.project(short).baseline, DashboardChartProjector.project(long).baseline)
        assertNotEquals(short.weights.last().movingAverage.kilograms, long.weights.last().movingAverage.kilograms)
        assertEquals(7, short.weights.last().movingAverage.recordedDays)
        assertEquals(30, long.weights.last().movingAverage.recordedDays)
    }

    @Test fun selectedRangeAndAverageContextAreIndependent() = runBlocking {
        val source = Source()
        val state = DashboardLoader(HealthDataRepository(source)).load(today, zone, 30, MovingAveragePeriod.THIRTY_DAYS) as DashboardState.Ready
        assertEquals(today.minusDays(58), source.ranges.minOf { it.startDate })
        assertEquals(today.minusDays(29), state.data.balances.range.startDate)
        assertEquals(30, state.data.balances.daily.size)
        assertEquals(-6000.0, state.data.balances.periodCumulative.last().kilocalories!!, 0.0)
        assertFalse(state.data.historyLimited)
    }

    @Test fun accessLimitedHistoryKeepsReadableDaysWithoutRepeatingCalorieQueries() = runBlocking {
        val source = Source()
        source.read = { if (it.startDate < today.minusDays(29)) throw SecurityException() else CalorieTotals(1.0, 2.0) }
        val state = DashboardLoader(HealthDataRepository(source)).load(today, zone, 30, MovingAveragePeriod.THIRTY_DAYS) as DashboardState.Ready
        assertTrue(state.data.historyLimited)
        assertEquals(30, state.data.weights.size)
        assertEquals(source.ranges.size, source.ranges.distinct().size)
        assertTrue(requireNotNull(state.data.today).previousSevenDays.accessRestrictedDates.isEmpty())
    }

    @Test fun noDataRemainsEmptyInsteadOfBecomingZero() = runBlocking {
        val source = Source().apply { read = { CalorieTotals(null, null) } }
        val state = DashboardLoader(HealthDataRepository(source)).load(today, zone, 7, MovingAveragePeriod.SEVEN_DAYS) as DashboardState.Ready
        assertFalse(state.data.hasData)
        assertNull(state.data.balances.periodCumulative.last().kilocalories)
    }

    @Test fun failureIsNotRetriedAsMissingData() = runBlocking {
        val source = Source().apply { read = { error("failure") } }
        assertEquals(DashboardState.Failed(HealthDataResult.Error), DashboardLoader(HealthDataRepository(source)).load(today, zone, 7, MovingAveragePeriod.SEVEN_DAYS))
        assertEquals(1, source.ranges.size)
    }

    @Test fun revokedPermissionsPreventDataExposure() = runBlocking {
        val source = Source().apply { read = { granted = emptySet(); CalorieTotals(1.0, 2.0) } }
        val state = DashboardLoader(HealthDataRepository(source)).load(today, zone, 7, MovingAveragePeriod.SEVEN_DAYS)
        assertEquals(DashboardState.Failed(HealthDataResult.PermissionsRequired(setOf("read"))), state)
    }

    @Test fun todayZeroOrMissingKeepsAxisAndOriginalValuesButHidesBalances() = runBlocking {
        for (intake in listOf(null, 0.0, 0.01)) {
            val source = Source().apply {
                read = { if (it.startDate == today) CalorieTotals(intake, 800.0) else CalorieTotals(2000.0, 2200.0) }
            }
            val data = (DashboardLoader(HealthDataRepository(source)).load(today, zone, 7, MovingAveragePeriod.SEVEN_DAYS) as DashboardState.Ready).data
            val status = requireNotNull(data.today)
            val end = today.plusDays(1)
            assertEquals(end, data.balances.range.endDateExclusive)
            assertEquals(end.minusDays(7), data.balances.range.startDate)
            assertEquals(end.minusDays(1), data.weights.last().date)
            assertEquals(intake, status.calories.intakeKilocalories)
            assertEquals(800.0, status.calories.burnedKilocalories)
            assertEquals(intake == 0.01, status.includesTodayBalance)
            assertEquals(intake, data.balances.daily.last().source.recorded.intakeKilocalories)
            if (intake != 0.01) {
                assertNull(data.balances.daily.last().kilocalories)
                assertFalse(data.balances.periodCumulative.any { it.date == today })
            }
            assertEquals(source.ranges.size, source.ranges.distinct().size)
            assertTrue(source.ranges.all { it.endDateExclusive == it.startDate.plusDays(1) })
        }
    }

    @Test fun intakeRefreshKeepsAxisAndWeightsWhileOnlyTodaysBalancesChange() = runBlocking {
        val source = populatedSource()
        val loader = DashboardLoader(HealthDataRepository(source))
        for (intake in listOf(null, 1200.0, null, 0.0)) {
            source.read = { if (it.startDate == today) CalorieTotals(intake, 1600.0) else CalorieTotals(2100.0, 2000.0) }
            val data = (loader.load(today, zone, 7, MovingAveragePeriod.SEVEN_DAYS) as DashboardState.Ready).data
            val chart = DashboardChartProjector.project(data)
            val end = today.plusDays(1)
            assertEquals(end.minusDays(7), chart.range.startDate)
            assertEquals(end, chart.range.endDateExclusive)
            assertEquals(7, chart.days.size)
            assertEquals(if (intake == 1200.0) 200.0 else 600.0, data.balances.periodCumulative.last().kilocalories!!, 0.0)
            assertTrue(data.balances.periodCumulative.all { it.missingDates.isEmpty() })
            chart.days.forEach { day ->
                assertEquals(day.date, day.daily?.date)
                if (day.date == today && intake != 1200.0) {
                    assertNull(day.periodCumulative)
                    assertNull(day.periodCumulativeY)
                    assertNull(day.dailyY)
                } else assertEquals(day.date, day.periodCumulative?.date)
                assertEquals(day.date, day.weight?.date)
            }
            assertNotNull(chart.days.last().weightY)
            assertNotNull(chart.days.last().movingAverageY)
            assertEquals(1300.0, requireNotNull(data.today).calorieSummary.intakeAllowanceKilocalories!!, 1e-9)
        }
    }

    @Test fun positiveTodaysIntakeWithMissingConsumptionKeepsTodayButNotAnInventedDailyBalance() = runBlocking {
        val source = Source().apply {
            read = { if (it.startDate == today) CalorieTotals(1200.0, null) else CalorieTotals(2100.0, 2000.0) }
        }
        val data = (DashboardLoader(HealthDataRepository(source)).load(today, zone, 7, MovingAveragePeriod.SEVEN_DAYS) as DashboardState.Ready).data
        assertEquals(today.plusDays(1), data.balances.range.endDateExclusive)
        assertNull(data.balances.daily.last().kilocalories)
        assertEquals(setOf(today), data.balances.periodCumulative.last().missingDates)
        assertEquals(600.0, data.balances.periodCumulative.last().kilocalories!!, 0.0)
    }

    @Test fun sevenDayInputsAreIndependentOfDisplayAndAverageAndExcludeToday() = runBlocking {
        val fixed = Clock.fixed(Instant.parse("2026-09-14T03:00:00Z"), ZoneOffset.UTC)
        val source = Source().apply {
            read = { CalorieTotals(it.startDate.dayOfMonth.toDouble(), 2000.0) }
        }
        val loader = DashboardLoader(HealthDataRepository(source), fixed)
        val short = (loader.load(today, zone, 7, MovingAveragePeriod.SEVEN_DAYS) as DashboardState.Ready).data
        val long = (loader.load(today, zone, 30, MovingAveragePeriod.THIRTY_DAYS) as DashboardState.Ready).data
        assertEquals(short.today, long.today)
        val status = requireNotNull(short.today)
        assertEquals(fixed.instant(), status.readStartedAt)
        assertEquals(today.minusDays(7), status.previousSevenDays.range.startDate)
        assertEquals(today, status.previousSevenDays.range.endDateExclusive)
        assertEquals((7..13).map(Int::toDouble), status.previousSevenDays.recorded.values.map { it.intakeKilocalories })
    }

    @Test fun restrictionOnSeventhPreviousDayIsNotSilentlyDroppedByShortDisplay() = runBlocking {
        val source = Source().apply {
            read = {
                if (it.startDate < today.minusDays(6)) throw SecurityException()
                if (it.startDate == today.minusDays(3)) CalorieTotals(null, null) else CalorieTotals(0.0, 0.0)
            }
        }
        // Positive intake today selects a seven-day trend that starts only six days ago.
        val earlierRead = source.read
        source.read = { if (it.startDate == today) CalorieTotals(1.0, 0.0) else earlierRead(it) }
        val data = (DashboardLoader(HealthDataRepository(source)).load(today, zone, 7, MovingAveragePeriod.SEVEN_DAYS) as DashboardState.Ready).data
        val past = requireNotNull(data.today).previousSevenDays
        assertEquals(setOf(today.minusDays(7)), past.accessRestrictedDates)
        assertFalse(past.recorded.containsKey(today.minusDays(7)))
        assertEquals(CalorieTotals(null, null), past.recorded[today.minusDays(3)]?.copy(metabolism = null))
        assertEquals(CalorieTotals(0.0, 0.0), past.recorded[today.minusDays(2)]?.copy(metabolism = null))
        assertEquals(6, past.recorded.size)
        assertTrue(data.historyLimited)
        assertEquals(source.ranges.size, source.ranges.distinct().size)
    }

    @Test fun zeroIntakeAloneTodayDoesNotCreateAnEmptyChart() = runBlocking {
        val source = Source().apply {
            read = { if (it.startDate == today) CalorieTotals(0.0, null) else CalorieTotals(null, null) }
        }
        val data = (DashboardLoader(HealthDataRepository(source)).load(today, zone, 7, MovingAveragePeriod.SEVEN_DAYS) as DashboardState.Ready).data
        assertFalse(data.hasData)
        assertEquals(0.0, data.today?.calories?.intakeKilocalories)
    }

    @Test fun consumptionOnlyTodayHasVisibleBarWithoutBalance() = runBlocking {
        val source = Source().apply {
            read = { if (it.startDate == today) CalorieTotals(null, 900.0) else CalorieTotals(null, null) }
        }
        val data = (DashboardLoader(HealthDataRepository(source)).load(today, zone, 7, MovingAveragePeriod.SEVEN_DAYS) as DashboardState.Ready).data
        assertTrue(data.hasData)
        assertNotNull(DashboardChartProjector.project(data).days.last().burnedY)
        assertTrue(requireNotNull(data.today).hasData)
        assertFalse(requireNotNull(data.today).previousSevenDays.hasRecordedData)
        assertTrue(data.balances.daily.all { it.kilocalories == null })
    }

    @Test fun todaysWeightCanInterpolateYesterdayButUnreportedIntakeStillExcludesConsumptionAnchor() = runBlocking {
        val source = populatedSource().apply {
            weights = weights.filter { it.time.atZone(zone).toLocalDate() != today.minusDays(1) }
            read = {
                when (it.startDate) {
                    today -> CalorieTotals(null, 800.0)
                    today.minusDays(1) -> CalorieTotals(1800.0, null)
                    else -> CalorieTotals(1800.0, 2200.0)
                }
            }
        }
        val data = (DashboardLoader(HealthDataRepository(source)).load(today, zone, 7, MovingAveragePeriod.SEVEN_DAYS) as DashboardState.Ready).data
        assertEquals(DisplayValue.Missing, data.balances.daily[data.balances.daily.lastIndex - 1].source.burned)
        assertTrue(data.weights[data.weights.lastIndex - 1].display is DisplayValue.Interpolated)
        assertEquals(DisplayValue.Recorded(70.0), data.weights.last().display)
        assertTrue(source.weightRanges.all { it.endDateExclusive == today.plusDays(1) })
        assertNull(requireNotNull(data.today).previousSevenDays.recorded.getValue(today.minusDays(1)).burnedKilocalories)
    }

    @Test fun onlyTodaysWeightStillProducesChartWithoutAnyCalorieBalances() = runBlocking {
        val source = populatedSource().apply {
            read = { CalorieTotals(null, null) }
            weights = weights.filter { it.time.atZone(zone).toLocalDate() == today }
        }
        val data = (DashboardLoader(HealthDataRepository(source)).load(today, zone, 7,
            MovingAveragePeriod.SEVEN_DAYS) as DashboardState.Ready).data
        assertTrue(data.hasData)
        val chart = DashboardChartProjector.project(data)
        assertEquals(7, chart.days.size)
        assertNotNull(chart.days.last().weightY)
        assertNotNull(chart.days.last().movingAverageY)
        assertTrue(chart.days.all { it.dailyY == null && it.periodCumulativeY == null })
    }

    @Test fun yesterdayZeroIsCalculatedNormallyWhileTodaysZeroIsSuppressedForAllPeriods() = runBlocking {
        val source = Source().apply { read = { CalorieTotals(0.0, 2000.0) } }
        for (days in listOf(7, 14, 30)) {
            val data = (DashboardLoader(HealthDataRepository(source)).load(today, zone, days,
                MovingAveragePeriod.SEVEN_DAYS) as DashboardState.Ready).data
            assertEquals(today.minusDays(days.toLong() - 1), data.balances.range.startDate)
            assertEquals(-2000.0, data.balances.daily[days - 2].kilocalories!!, 0.0)
            assertNull(data.balances.daily.last().kilocalories)
            assertEquals(days - 1, data.balances.periodCumulative.size)
            assertEquals(-2000.0 * (days - 1), data.balances.periodCumulative.last().kilocalories!!, 0.0)
            assertTrue(data.balances.periodCumulative.all { it.missingDates.isEmpty() })
        }
    }

    @Test fun weightHistoryFallbackDoesNotRereadOrTrimSevenDayCalories() = runBlocking {
        val source = Source().apply {
            onWeightRead = { if (it.startDate < today.minusDays(6)) throw SecurityException() }
        }
        val data = (DashboardLoader(HealthDataRepository(source)).load(today, zone, 7, MovingAveragePeriod.SEVEN_DAYS) as DashboardState.Ready).data
        assertEquals(2, source.weightRanges.size)
        assertEquals(data.balances.range, source.weightRanges.last())
        assertEquals(7, requireNotNull(data.today).previousSevenDays.recorded.size)
        assertEquals(source.ranges.size, source.ranges.distinct().size)
        assertTrue(data.historyLimited)
    }

    @Test fun requiredRangeRestrictionAndLateFailuresDoNotExposeTodayOnlySuccess() = runBlocking {
        for (failure in listOf(SecurityException(), IllegalStateException())) {
            val source = Source().apply {
                read = { if (it.startDate == today.minusDays(1)) throw failure else CalorieTotals(1.0, 2.0) }
            }
            val state = DashboardLoader(HealthDataRepository(source)).load(today, zone, 7, MovingAveragePeriod.SEVEN_DAYS)
            val reason = if (failure is SecurityException) HealthDataResult.AccessDenied else HealthDataResult.Error
            assertEquals(DashboardState.Failed(reason), state)
            assertTrue(source.weightRanges.isEmpty())
        }
    }

    @Test fun missingPermissionsAndLateRevocationDoNotExposeSnapshot() = runBlocking {
        val source = Source().apply { granted = emptySet() }
        val loader = DashboardLoader(HealthDataRepository(source))
        assertTrue(loader.load(today, zone, 7, MovingAveragePeriod.SEVEN_DAYS) is DashboardState.Failed)
        assertTrue(source.ranges.isEmpty())
        source.granted = source.requiredPermissions
        source.onWeightRead = { source.granted = emptySet(); throw SecurityException() }
        assertEquals(DashboardState.Failed(HealthDataResult.PermissionsRequired(setOf("read"))),
            loader.load(today, zone, 7, MovingAveragePeriod.SEVEN_DAYS))
        assertEquals(1, source.weightRanges.size)
    }

    @Test fun cancelledOlderReadCannotReturnTodaysOldSnapshot() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = Source().apply { onWeightRead = { entered.complete(Unit); release.await() } }
        val loader = DashboardLoader(HealthDataRepository(source))
        val older = async { loader.load(today, zone, 7, MovingAveragePeriod.SEVEN_DAYS) }
        entered.await()
        older.cancelAndJoin()
        source.onWeightRead = {}
        source.read = { CalorieTotals(3000.0, 2300.0) }
        val latest = loader.load(today, zone, 7, MovingAveragePeriod.SEVEN_DAYS) as DashboardState.Ready
        release.complete(Unit)
        assertTrue(older.isCancelled)
        assertEquals(3000.0, requireNotNull(latest.data.today).calories.intakeKilocalories)
    }

    @Test fun todayReadCoversWholeLocalDayIncludingDaylightSavingBoundary() = runBlocking {
        val date = LocalDate.of(2026, 3, 8)
        val dstZone = ZoneId.of("America/New_York")
        val readTime = date.atTime(12, 0).atZone(dstZone).toInstant()
        val source = Source()
        val data = (DashboardLoader(HealthDataRepository(source), Clock.fixed(readTime, ZoneOffset.UTC))
            .load(date, dstZone, 7, MovingAveragePeriod.SEVEN_DAYS) as DashboardState.Ready).data
        val request = source.ranges.first()
        assertEquals(date.atStartOfDay(dstZone).toInstant(), request.startTime)
        assertEquals(date.plusDays(1).atStartOfDay(dstZone).toInstant(), request.endTime)
        assertEquals(23L, Duration.between(request.startTime, request.endTime).toHours())
        assertTrue(request.endTime > readTime)
        assertEquals(readTime, requireNotNull(data.today).readStartedAt)
    }

    @Test(expected = CancellationException::class) fun cancellationPropagates() { runBlocking {
        val source = Source().apply { read = { throw CancellationException() } }
        DashboardLoader(HealthDataRepository(source)).load(today, zone, 7, MovingAveragePeriod.SEVEN_DAYS)
    } }
}
