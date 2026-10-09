package com.example.trackmate

import android.Manifest
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.Space
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.trackmate.services.GeocodeResult
import com.example.trackmate.services.LatLng
import com.example.trackmate.services.NewTrackRequest
import com.example.trackmate.services.PlanMessage
import com.example.trackmate.services.PlanProgress
import com.example.trackmate.services.PlanRequest
import com.example.trackmate.services.PlannedRoute
import com.example.trackmate.services.PlannedTrackFile
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec
import com.google.android.material.progressindicator.IndeterminateDrawable
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import retrofit2.Call
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Initial view when the location isn't available
private val DEFAULT_CENTER = GeoPoint(44.8015, 10.3279) // Parma
private const val DEFAULT_ZOOM = 13.0
private const val MY_LOCATION_ZOOM = 14.0
private const val MY_LOCATION_BUTTON_ZOOM = 16.0 // as the other maps' location button
private const val SEARCH_MIN_CHARS = 3
private const val SEARCH_DEBOUNCE_MS = 300L
// Same as the server's, checked here to tell the user before planning
private const val MAX_LEG_METERS = 50_000.0
private const val EARTH_RADIUS_METERS = 6_371_008.8
// A few meters off is just the road's width: no connector line
private const val CONNECTOR_MIN_METERS = 10.0
private const val SHEET_MAX_HEIGHT_FRACTION = 0.6
private const val FIT_MAX_ZOOM = 16.0
private const val FIT_PADDING_DP = 40

/** A point of the route. `label` is the place name or "My location", null for map taps and coordinates. */
data class PlanPoint(val lat: Double, val lng: Double, val label: String? = null)

private class PlanError(message: String) : Exception(message)

/**
 * The web planner (public/plan.html and js/plan.js in the backend) in its phone layout: same sections, texts and
 * behaviour. The only addition is Navigate, which saves the route as a track and opens it in TrackNavigation.
 */
class TrackPlanner : Fragment() {

    /** One field of the points list. The point outlives the fragment's view, the views and the marker don't. */
    private inner class Row {
        var point: PlanPoint? = null
        lateinit var element: View
        lateinit var field: TextInputLayout
        lateinit var input: EditText
        lateinit var pin: TextView
        lateinit var name: TextView
        lateinit var search: PlaceSearch
        var marker: Marker? = null
    }

    // #region state, kept while the fragment is on the back stack (e.g. during a navigation started here)
    private val rows = mutableListOf<Row>()
    private var activeRow: Row? = null // the row a map tap fills
    private var policy = "safest"
    // Changing it clears the route, so it is also the vehicle the current route was planned with
    private var vehicle = Vehicle.BICYCLE
    // Safety filters on by default, as on the web
    private val filterStates = linkedMapOf("cyclewaysOnly" to true, "avoidLts4" to true, "avoidUnpaved" to false)
    private var currentRoute: PlannedRoute? = null
    private var savedTrackId: Int? = null // the current route's track, once saved
    private var planSequence = 0 // ignore answers of outdated requests
    private var planJob: Job? = null
    private var planCall: Call<ResponseBody>? = null
    private var planning = false
    private var mapCenter: GeoPoint? = null
    private var mapZoom = DEFAULT_ZOOM
    private var mapOrientation = 0f
    private var askedForLocation = false
    // #endregion

    private lateinit var mapView: MapView
    private lateinit var myLocationOverlay: AnimatedMyLocationOverlay
    private lateinit var btnCompass: FloatingActionButton
    private lateinit var sheet: View
    private lateinit var sheetScroll: NestedScrollView
    private lateinit var mapControls: View
    private lateinit var sheetBehavior: BottomSheetBehavior<View>
    private lateinit var pointList: LinearLayout
    private lateinit var txtPointsHint: TextView
    private lateinit var policyGroup: MaterialButtonToggleGroup
    private lateinit var safetyGroup: View
    private lateinit var btnPlan: MaterialButton
    private lateinit var txtStatus: TextView
    private lateinit var result: View
    private lateinit var statsGrid: LinearLayout
    private lateinit var legList: LinearLayout
    private lateinit var btnSave: MaterialButton
    private lateinit var btnNavigate: MaterialButton
    private lateinit var spinner: IndeterminateDrawable<CircularProgressIndicatorSpec>
    private lateinit var mutedText: ColorStateList
    private var routeLine: Polyline? = null
    private val connectorLines = mutableListOf<Polyline>()
    private var compassAnimator: ValueAnimator? = null
    private var settingText = false // programmatic setText, not typing

    private val moshi = Moshi.Builder().build()
    private val routeService by lazy { (requireActivity() as MainActivity).routeService }
    private val trackService by lazy { (requireActivity() as MainActivity).trackService }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && view != null) {
                myLocationOverlay.enableMyLocation()
                useMyLocationAsStart()
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_track_planner, container, false)
        mapView = view.findViewById(R.id.mapView)
        btnCompass = view.findViewById(R.id.btnCompass)
        sheet = view.findViewById(R.id.sheet)
        sheetScroll = view.findViewById(R.id.sheetScroll)
        mapControls = view.findViewById(R.id.mapControls)
        pointList = view.findViewById(R.id.pointList)
        txtPointsHint = view.findViewById(R.id.txtPointsHint)
        policyGroup = view.findViewById(R.id.policyGroup)
        safetyGroup = view.findViewById(R.id.safetyGroup)
        btnPlan = view.findViewById(R.id.btnPlan)
        txtStatus = view.findViewById(R.id.txtStatus)
        result = view.findViewById(R.id.result)
        statsGrid = view.findViewById(R.id.statsGrid)
        legList = view.findViewById(R.id.legList)
        btnSave = view.findViewById(R.id.btnSave)
        btnNavigate = view.findViewById(R.id.btnNavigate)
        mutedText = txtStatus.textColors

        setupMap(view)
        setupSheet(view)
        setupOptions(view)

        val firstTime = rows.isEmpty()
        if (firstTime) {
            rows.add(Row())
            rows.add(Row())
            activeRow = rows[0]
        }
        rows.forEach { addRowView(it) }
        renumberRows()
        rows.forEach { drawMarker(it) }

        view.findViewById<View>(R.id.btnAddStop).setOnClickListener { addStop() }
        view.findViewById<ImageButton>(R.id.btnReverse).setOnClickListener { reverse() }
        btnPlan.setOnClickListener { plan() }
        btnSave.setOnClickListener { askTrackName { } }
        btnNavigate.setOnClickListener {
            savedTrackId?.let { openNavigation(it) } ?: askTrackName { openNavigation(it) }
        }

        refreshPointsHint()
        setPlanning(false)
        currentRoute?.let { showRoute(it, fit = false) }

        if (firstTime) startFromMyLocation()
        return view
    }

    // #region map
    private fun setupMap(view: View) {
        val context = requireContext()
        mapView.setTileSource(tileSourceFor(getSavedMapStyle(context)))
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(mapZoom)
        mapView.controller.setCenter(mapCenter ?: DEFAULT_CENTER)
        mapView.mapOrientation = mapOrientation

        // Bottom of the overlay list: markers and lines above get taps first
        mapView.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                onMapTap(p)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))
        // Two fingers turn the map, as on the web; the compass then shows
        mapView.overlays.add(object : RotationGestureOverlay(mapView) {
            override fun onRotate(deltaAngle: Float) {
                super.onRotate(deltaAngle)
                updateCompass()
            }
        }.apply { isEnabled = true })

        myLocationOverlay = AnimatedMyLocationOverlay(GpsMyLocationProvider(context), mapView)
        applyPrimaryLocationIcons(context, myLocationOverlay)
        mapView.overlays.add(myLocationOverlay)
        mapView.overlays.add(buildCopyrightOverlay(context))

        setupMapControls(
            fragment = this,
            mapView = mapView,
            myLocationOverlay = myLocationOverlay,
            btnLayers = view.findViewById(R.id.btnLayers),
            btnMyLocation = view.findViewById(R.id.btnMyLocation),
            btnZoomIn = view.findViewById(R.id.btnZoomIn),
            btnZoomOut = view.findViewById(R.id.btnZoomOut),
            centerOnLocation = { showAboveSheet(it, MY_LOCATION_BUTTON_ZOOM, animate = true) }
        )
        // The icon carries its own colors
        btnCompass.imageTintList = null
        btnCompass.setOnClickListener { turnNorth() }
        updateCompass()

        // Touching the map leaves the fields, like a click on the web map
        mapView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) leaveFields()
            false
        }
    }

    private fun updateCompass() {
        val turned = ((mapView.mapOrientation % 360f) + 360f) % 360f != 0f
        btnCompass.isVisible = turned
        // Same as MapCompassController: the needle points at north on screen
        btnCompass.rotation = mapView.mapOrientation
    }

    private fun turnNorth() {
        compassAnimator?.cancel()
        val from = mapView.mapOrientation
        // The short way round
        val to = from - (((from % 360f) + 540f) % 360f - 180f)
        compassAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = 300L
            addUpdateListener {
                mapView.mapOrientation = it.animatedValue as Float
                updateCompass()
            }
            doOnEnd {
                mapView.mapOrientation = 0f
                updateCompass()
            }
            start()
        }
    }

    private fun onMapTap(p: GeoPoint) {
        val row = activeRow ?: rows.first()
        setPoint(row, PlanPoint(p.latitude, p.longitude))
        activeRow = firstEmptyRow() ?: row
    }

    private fun startFromMyLocation() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            useMyLocationAsStart()
        } else if (!askedForLocation) {
            askedForLocation = true
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /** The start defaults to the current location, as on the web. */
    @Suppress("MissingPermission") // checked by the callers
    private fun useMyLocationAsStart() {
        LocationServices.getFusedLocationProviderClient(requireContext())
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location == null || view == null || rows.first().point != null) return@addOnSuccessListener
                showAboveSheet(GeoPoint(location.latitude, location.longitude), MY_LOCATION_ZOOM, animate = false)
                setPoint(rows.first(), PlanPoint(location.latitude, location.longitude, getString(R.string.plan_my_location)))
                if (activeRow === rows.first()) activeRow = firstEmptyRow() ?: rows.first()
            }
    }

    private fun drawMarker(row: Row) {
        val point = row.point ?: return
        val marker = row.marker ?: Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            infoWindow = null
            isDraggable = true
            setOnMarkerClickListener { _, _ -> true }
            setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                override fun onMarkerDrag(marker: Marker) {}
                override fun onMarkerDragStart(marker: Marker) {}
                override fun onMarkerDragEnd(marker: Marker) {
                    setPoint(row, PlanPoint(marker.position.latitude, marker.position.longitude))
                }
            })
            // Above the route lines and the location cursor (which would hide "My location"), below the copyright
            mapView.overlays.add(mapView.overlays.size - 1, this)
            row.marker = this
        }
        marker.position = GeoPoint(point.lat, point.lng)
        marker.icon = createLetterMarkerIcon(requireContext(), pinLabel(row))
        mapView.invalidate()
    }

    private fun removeMarker(row: Row) {
        row.marker?.let { mapView.overlays.remove(it) }
        row.marker = null
        mapView.invalidate()
    }

    private fun routePolyline(widthDp: Float): Polyline = Polyline().apply {
        outlinePaint.color = ContextCompat.getColor(
            requireContext(),
            com.google.android.material.R.color.design_default_color_primary
        )
        outlinePaint.strokeWidth = widthDp * resources.displayMetrics.density
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.strokeJoin = Paint.Join.ROUND
        // Taps on a line still set a point
        setOnClickListener { _, _, _ -> false }
    }

    /** Dashed lines from each point to where the route starts or ends. A stop can have two. */
    private fun drawConnectors(route: PlannedRoute) {
        val density = resources.displayMetrics.density
        route.legs.forEachIndexed { i, leg ->
            listOf(rows[i].point to leg.from, rows[i + 1].point to leg.to).forEach { (point, end) ->
                if (point == null) return@forEach
                val from = GeoPoint(point.lat, point.lng)
                val to = GeoPoint(end.lat, end.lng)
                if (from.distanceToAsDouble(to) <= CONNECTOR_MIN_METERS) return@forEach
                val line = routePolyline(3f).apply {
                    // Round dots, as the web's dash pattern
                    outlinePaint.pathEffect = DashPathEffect(floatArrayOf(1.5f * density, 6f * density), 0f)
                    setPoints(listOf(from, to))
                }
                connectorLines.add(line)
                mapView.overlays.add(2, line)
            }
        }
    }

    /** Fits the route and the points in the part of the map above the sheet and left of the map buttons. */
    private fun fitRoute(points: List<GeoPoint>) {
        mapView.post {
            if (view == null || points.isEmpty()) return@post
            val padding = FIT_PADDING_DP * resources.displayMetrics.density
            val rightPadding = max(padding, (mapView.width - mapControls.left).toFloat())
            val width = (mapView.width - padding - rightPadding).toInt().coerceAtLeast(1)
            val height = (visibleMapHeight() - 2 * padding).toInt().coerceAtLeast(1)
            val box = BoundingBox.fromGeoPointsSafe(points)
            val tileSystem = MapView.getTileSystem()
            var zoom = tileSystem.getBoundingBoxZoom(box, width, height)
            if (zoom.isNaN() || zoom.isInfinite()) zoom = FIT_MAX_ZOOM
            zoom = zoom.coerceIn(mapView.minZoomLevel, FIT_MAX_ZOOM)
            val mapSize = TileSystem.MapSize(zoom)
            val north = tileSystem.getMercatorYFromLatitude(box.latNorth, mapSize, false).toDouble()
            val south = tileSystem.getMercatorYFromLatitude(box.latSouth, mapSize, false).toDouble()
            val middle = GeoPoint(tileSystem.getLatitudeFromY01((north + south) / 2 / mapSize, false), box.centerLongitude)
            showAboveSheet(middle, zoom, animate = true, shiftRight = (rightPadding - padding) / 2.0)
        }
    }

    /** Centers `point` in the part of the map above the sheet, `shiftRight` pixels right of the middle. */
    private fun showAboveSheet(point: GeoPoint, zoom: Double, animate: Boolean, shiftRight: Double = 0.0) {
        val tileSystem = MapView.getTileSystem()
        val mapSize = TileSystem.MapSize(zoom)
        // The map's center is below the visible part's center, by half the covered height
        val y = tileSystem.getMercatorYFromLatitude(point.latitude, mapSize, false) +
            (mapView.height - visibleMapHeight()) / 2.0
        val x = tileSystem.getMercatorXFromLongitude(point.longitude, mapSize, false) + shiftRight
        val center = GeoPoint(
            tileSystem.getLatitudeFromY01(y / mapSize, false),
            tileSystem.getLongitudeFromX01(x / mapSize, false)
        )
        if (animate) {
            mapView.controller.animateTo(center, zoom, 500L)
        } else {
            mapView.controller.setZoom(zoom)
            mapView.controller.setCenter(center)
        }
    }

    private fun visibleMapHeight(): Int = if (mapView.height == 0) 0 else sheet.top.coerceIn(1, mapView.height)
    // #endregion

    // #region sheet and options
    private fun setupSheet(view: View) {
        sheetBehavior = BottomSheetBehavior.from(sheet)
        sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        val root = view.findViewById<View>(R.id.plannerRoot)
        root.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                // After this layout pass: a new max height during layout is ignored
                root.post {
                    sheetBehavior.maxHeight = ((bottom - top) * SHEET_MAX_HEIGHT_FRACTION).toInt()
                    sheet.requestLayout()
                }
            }
        }
        // Sheet: the progress spinner inside the Plan button
        val spec = CircularProgressIndicatorSpec(
            requireContext(), null, 0,
            com.google.android.material.R.style.Widget_MaterialComponents_CircularProgressIndicator_ExtraSmall
        ).apply { indicatorColors = intArrayOf(Color.WHITE) }
        spinner = IndeterminateDrawable.createCircularDrawable(requireContext(), spec)
    }

    private fun setupOptions(view: View) {
        val vehicleInput = view.findViewById<MaterialAutoCompleteTextView>(R.id.vehicleInput)
        fun showVehicles(vehicles: List<Vehicle>) {
            vehicleInput.setSimpleItems(vehicles.map { getString(it.label) }.toTypedArray())
            vehicleInput.setText(getString(vehicle.label), false)
            vehicleInput.setOnItemClickListener { _, _, position, _ ->
                if (vehicles[position] == vehicle) return@setOnItemClickListener
                vehicle = vehicles[position]
                updateFilterGroups()
                routeChanged()
            }
        }
        showVehicles(listOf(vehicle))
        // As on the web: only the vehicles the server can plan for
        viewLifecycleOwner.lifecycleScope.launch {
            val plannable = runCatching { routeService.vehicles().body() }.getOrNull()
                ?.filter { it.plannable }
                ?.mapNotNull { Vehicle.fromId(it.id) }
            if (!plannable.isNullOrEmpty()) {
                if (vehicle !in plannable) vehicle = plannable.first()
                showVehicles(plannable)
            }
        }

        policyGroup.check(if (policy == "safest") R.id.btnSafest else R.id.btnShortest)
        policyGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            policy = if (id == R.id.btnSafest) "safest" else "shortest"
            updateFilterGroups()
            routeChanged()
        }

        mapOf(
            R.id.cbCyclewaysOnly to "cyclewaysOnly",
            R.id.cbAvoidLts4 to "avoidLts4",
            R.id.cbAvoidUnpaved to "avoidUnpaved"
        ).forEach { (id, name) ->
            view.findViewById<MaterialCheckBox>(id).apply {
                isChecked = filterStates.getValue(name)
                setOnCheckedChangeListener { _, checked ->
                    filterStates[name] = checked
                    routeChanged()
                }
            }
        }
        updateFilterGroups()
    }

    /** The Safety group is for Safest only; the Preferences group for the bicycle, the only vehicle. */
    private fun updateFilterGroups() {
        safetyGroup.isVisible = policy == "safest"
    }

    /** Hidden filters keep their state but are not sent. */
    private fun selectedFilters(): Map<String, Boolean> =
        filterStates.filterKeys { it == "avoidUnpaved" || policy == "safest" }
    // #endregion

    // #region rows
    private fun addRowView(row: Row, index: Int = pointList.childCount) {
        val element = layoutInflater.inflate(R.layout.item_plan_point, pointList, false)
        row.element = element
        row.field = element.findViewById(R.id.pointField)
        row.input = element.findViewById(R.id.pointInput)
        row.pin = element.findViewById(R.id.pin)
        row.name = element.findViewById(R.id.pointName)
        row.search = PlaceSearch(row)
        pointList.addView(element, index)
        setInputText(row, pointText(row.point))
    }

    private fun isStop(row: Row) = row !== rows.first() && row !== rows.last()

    /** The same names the server uses in its messages. */
    private fun rowName(index: Int): String = when (index) {
        0 -> getString(R.string.plan_start)
        rows.size - 1 -> getString(R.string.plan_destination)
        else -> getString(R.string.plan_stop, index)
    }

    private fun pinLabel(row: Row): String = when (row) {
        rows.first() -> "A"
        rows.last() -> "B"
        else -> rows.indexOf(row).toString()
    }

    /** Names, pins and markers follow the rows' positions. Only stops can be removed. */
    private fun renumberRows() {
        rows.forEachIndexed { index, row ->
            row.name.text = rowName(index)
            row.pin.text = pinLabel(row)
            row.marker?.icon = createLetterMarkerIcon(requireContext(), pinLabel(row))
            if (isStop(row)) {
                row.field.endIconMode = TextInputLayout.END_ICON_CUSTOM
                row.field.setEndIconDrawable(R.drawable.ic_close_24)
                row.field.endIconContentDescription = getString(R.string.plan_remove_stop)
                row.field.setEndIconOnClickListener { removeStop(row) }
            } else {
                row.field.endIconMode = TextInputLayout.END_ICON_NONE
            }
        }
        mapView.invalidate()
    }

    private fun firstEmptyRow(): Row? = rows.firstOrNull { it.point == null }

    private fun addStop() {
        val row = Row()
        rows.add(rows.size - 1, row)
        addRowView(row, rows.size - 2)
        renumberRows()
        activeRow = row
        row.input.requestFocus()
        requireContext().getSystemService(InputMethodManager::class.java)
            .showSoftInput(row.input, InputMethodManager.SHOW_IMPLICIT)
        routeChanged()
    }

    private fun removeStop(row: Row) {
        row.search.dismiss()
        removeMarker(row)
        pointList.removeView(row.element)
        rows.remove(row)
        renumberRows()
        if (activeRow === row) activeRow = firstEmptyRow() ?: rows.last()
        routeChanged()
    }

    /** Rows stay where they are, their points swap ends. */
    private fun reverse() {
        val reversed = rows.map { it.point }.reversed()
        rows.forEachIndexed { i, row ->
            removeMarker(row)
            row.point = reversed[i]
            setInputText(row, pointText(row.point))
            drawMarker(row)
        }
        routeChanged()
    }

    private fun setPoint(row: Row, point: PlanPoint?, fly: Boolean = false) {
        row.point = point
        setInputText(row, pointText(point))
        if (point != null) drawMarker(row) else removeMarker(row)
        if (fly && point != null) {
            showAboveSheet(GeoPoint(point.lat, point.lng), max(mapView.zoomLevelDouble, 14.0), animate = true)
        }
        routeChanged()
    }

    private fun setInputText(row: Row, text: String) {
        settingText = true
        row.input.setText(text)
        settingText = false
    }

    /** Leaves the fields: hides the keyboard, and unpicked text goes back to the point. */
    private fun leaveFields() {
        val focused = view?.findFocus() ?: return
        requireContext().getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(focused.windowToken, 0)
        focused.clearFocus()
    }
    // #endregion

    // #region planning
    /** Nothing is planned (or downloaded on the server) until the user asks. */
    private fun routeChanged() {
        cancelPlanning()
        clearRoute()
        showStatus("")
        refreshPointsHint()
        setPlanning(false)
    }

    /** Problems with the points replace the 50 km hint, right under the fields they're about. */
    private fun refreshPointsHint() {
        val problem = routeProblem()
        txtPointsHint.text = problem ?: getString(R.string.plan_leg_limit_hint)
        if (problem != null) txtPointsHint.setTextColor(errorColor()) else txtPointsHint.setTextColor(mutedText)
        txtPointsHint.setTypeface(null, if (problem != null) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun routeProblem(): String? {
        rows.forEach { setTooFar(it, false) }
        if (rows.first().point == null || rows.last().point == null) return null // nothing to say yet
        if (rows.any { it.point == null }) return getString(R.string.plan_empty_stop)
        for (i in 0 until rows.size - 1) {
            val meters = haversineMeters(rows[i].point!!, rows[i + 1].point!!)
            if (meters > MAX_LEG_METERS) {
                setTooFar(rows[i], true)
                setTooFar(rows[i + 1], true)
                return getString(R.string.plan_leg_too_far, rowName(i), rowName(i + 1), formatNumber(meters / 1000, 0))
            }
        }
        return null
    }

    private fun setTooFar(row: Row, tooFar: Boolean) {
        val density = resources.displayMetrics.density
        row.field.setBoxStrokeColorStateList(
            // With states: a plain color would only change the focused underline
            if (tooFar) ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(errorColor(), errorColor())
            )
            else ContextCompat.getColorStateList(
                requireContext(),
                com.google.android.material.R.color.mtrl_filled_stroke_color
            )!!
        )
        row.field.boxStrokeWidth = ((if (tooFar) 2 else 1) * density).roundToInt()
    }

    private fun errorColor() =
        ContextCompat.getColor(requireContext(), com.google.android.material.R.color.design_default_color_error)

    /** Closing the call also stops the server downloading map data for it. The blocked stream read
     * doesn't see a coroutine cancel, so the call is cancelled directly. */
    private fun cancelPlanning() {
        planSequence++
        planCall?.cancel()
        planCall = null
        planJob?.cancel()
        planJob = null
    }

    private fun setPlanning(planning: Boolean) {
        this.planning = planning
        btnPlan.text = getString(if (planning) R.string.plan_planning else R.string.plan_plan)
        btnPlan.icon = if (planning) spinner else null
        // Stays teal while planning, as on the web: clicks are ignored instead
        btnPlan.isEnabled = planning || (rows.all { it.point != null } && routeProblem() == null)
    }

    private fun clearRoute() {
        currentRoute = null
        savedTrackId = null
        result.isVisible = false
        routeLine?.let { mapView.overlays.remove(it) }
        routeLine = null
        connectorLines.forEach { mapView.overlays.remove(it) }
        connectorLines.clear()
        mapView.invalidate()
    }

    private fun showStatus(text: String, isError: Boolean = false) {
        txtStatus.text = text
        if (isError) txtStatus.setTextColor(errorColor()) else txtStatus.setTextColor(mutedText)
        txtStatus.isVisible = text.isNotEmpty()
    }

    private fun plan() {
        if (planning || rows.any { it.point == null } || routeProblem() != null) return
        leaveFields()
        cancelPlanning()
        val sequence = planSequence
        clearRoute()
        setPlanning(true)
        showStatus(getString(R.string.plan_planning))
        val points = rows.map { LatLng(it.point!!.lat, it.point!!.lng) }
        val request = PlanRequest(
            start = points.first(),
            via = points.subList(1, points.size - 1),
            end = points.last(),
            vehicle = vehicle.id,
            policy = policy,
            filters = selectedFilters()
        )
        val call = routeService.plan(request)
        planCall = call
        planJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val route = withContext(Dispatchers.IO) {
                    readPlanStream(call) { progress ->
                        withContext(Dispatchers.Main) {
                            if (sequence == planSequence) showProgress(progress)
                        }
                    }
                }
                if (sequence != planSequence) return@launch
                showRoute(route, fit = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (sequence != planSequence) return@launch
                showStatus(e.message?.takeIf { e is PlanError && it.isNotBlank() } ?: getString(R.string.error_generic), true)
            } finally {
                if (sequence == planSequence) setPlanning(false)
            }
        }
    }

    /** NDJSON: progress lines while map data downloads, then the route, or an error with the server's message. */
    private suspend fun readPlanStream(call: Call<ResponseBody>, onProgress: suspend (PlanProgress) -> Unit): PlannedRoute {
        val response = call.execute()
        if (!response.isSuccessful) throw PlanError(response.errorBody()?.string().orEmpty())
        val adapter = moshi.adapter(PlanMessage::class.java)
        var route: PlannedRoute? = null
        response.body()!!.use { body ->
            val source = body.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                val message = adapter.fromJson(line) ?: continue
                message.error?.let { throw PlanError(it) }
                message.route?.let { route = it }
                message.progress?.let { if (it.total > 0) onProgress(it) }
            }
        }
        return route ?: throw PlanError("")
    }

    private fun showProgress(progress: PlanProgress) {
        showStatus(
            if (progress.done < progress.total) getString(R.string.plan_downloading, progress.done, progress.total)
            else getString(R.string.plan_calculating)
        )
    }

    private fun showRoute(route: PlannedRoute, fit: Boolean) {
        currentRoute = route
        val track = route.track.map { GeoPoint(it.latitude, it.longitude) }
        drawConnectors(route)
        routeLine = routePolyline(5f).apply { setPoints(track) }
        mapView.overlays.add(2, routeLine)
        mapView.invalidate()

        // Soft stats are only sent when their filter is on
        val stats = listOfNotNull(
            Triple(R.string.plan_distance, null, formatDistance(route.distance)),
            Triple(R.string.plan_duration, null, formatDuration(route.duration)),
            route.offBikeways?.let { Triple(R.string.plan_off_bikeways, R.string.plan_off_bikeways_hint, formatDistance(it)) },
            route.highStress?.let { Triple(R.string.plan_high_stress, R.string.plan_high_stress_hint, formatDistance(it)) },
            route.unpaved?.let { Triple(R.string.plan_unpaved, R.string.plan_unpaved_hint, formatDistance(it)) }
        )
        statsGrid.removeAllViews()
        stats.chunked(2).forEach { pair ->
            val line = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { (label, hint, value) ->
                val item = layoutInflater.inflate(R.layout.item_plan_stat, line, false)
                item.findViewById<TextView>(R.id.statLabel).text = getString(label)
                item.findViewById<TextView>(R.id.statValue).text = value
                hint?.let { item.tooltipText = getString(it) }
                line.addView(item)
            }
            if (pair.size == 1) line.addView(Space(requireContext()), LinearLayout.LayoutParams(0, 0, 1f))
            statsGrid.addView(line)
        }

        legList.removeAllViews()
        if (route.legs.size > 1) {
            route.legs.forEachIndexed { i, leg ->
                val item = layoutInflater.inflate(R.layout.item_plan_leg, legList, false)
                item.findViewById<TextView>(R.id.legName).text = "${rowName(i)} → ${rowName(i + 1)}"
                item.findViewById<TextView>(R.id.legValue).text =
                    "${formatDistance(leg.distance)} · ${formatDuration(leg.duration)}"
                legList.addView(item)
            }
        }
        legList.isVisible = route.legs.size > 1
        result.isVisible = true
        showStatus("")
        if (fit) {
            fitRoute(track + rows.mapNotNull { row -> row.point?.let { GeoPoint(it.lat, it.lng) } })
            // On a phone the result starts below the sheet's fold
            sheetScroll.post { sheetScroll.smoothScrollTo(0, result.top) }
        }
    }
    // #endregion

    // #region save and navigate
    private fun askTrackName(onSaved: (Int) -> Unit) {
        val route = currentRoute ?: return
        // No vehicle toggle: the track is for the vehicle the route was planned with
        showTrackNameDialog(
            title = getString(R.string.plan_save_prompt),
            name = rows.joinToString(" → ") { pointText(it.point) },
            vehicle = null,
            saveLabel = getString(R.string.plan_save_button),
            cancelLabel = getString(R.string.plan_cancel)
        ) { name, _, done -> saveRoute(route, name, onSaved, done) }
    }

    /**
     * As on the web: the track, then its file in the recorded tracks' format. No quest: those are for recordings.
     * [done] gets null once saved, otherwise the error, which the name dialog shows.
     */
    private fun saveRoute(route: PlannedRoute, name: String, onSaved: (Int) -> Unit, done: (String?) -> Unit) {
        setSaving(true)
        // The fragment's scope, not the view's: a track without its file must still be deleted
        lifecycleScope.launch {
            var trackId: Int? = null
            try {
                val created = trackService.createTrack(NewTrackRequest(name, vehicle.id))
                trackId = created.body()?.id
                if (!created.isSuccessful || trackId == null) throw PlanError(created.errorBody()?.string().orEmpty())
                val json = moshi.adapter(PlannedTrackFile::class.java)
                    .toJson(PlannedTrackFile(route.track, route.distance, route.duration))
                val file = MultipartBody.Part.createFormData(
                    "file", "track.json", json.toRequestBody("application/json".toMediaType())
                )
                val uploaded = trackService.uploadTrack(file, trackId)
                if (!uploaded.isSuccessful) throw PlanError(uploaded.errorBody()?.string().orEmpty())
                if (currentRoute === route) savedTrackId = trackId
                done(null)
                if (view != null) showStatus(getString(R.string.plan_saved))
                onSaved(trackId)
            } catch (e: Exception) {
                // Don't leave a track without its file behind
                trackId?.let { id ->
                    withContext(NonCancellable) { runCatching { trackService.deleteTrack(id) } }
                }
                if (e is CancellationException) throw e
                val error = e.message?.takeIf { e is PlanError && it.isNotBlank() } ?: getString(R.string.track_save_failed)
                done(error)
                if (view != null) showStatus(error, true)
            } finally {
                if (view != null) setSaving(false)
            }
        }
    }

    private fun setSaving(saving: Boolean) {
        btnSave.isEnabled = !saving
        btnNavigate.isEnabled = !saving
    }

    private fun openNavigation(trackId: Int) {
        if (view == null || !isResumed) return
        findNavController().navigate(TrackPlannerDirections.actionTrackPlannerToTrackNavigation(trackId))
    }
    // #endregion

    // #region place search
    private enum class SuggestionKind { RESULT, MESSAGE, SEARCH_ALL }

    private class Suggestion(val text: String, val kind: SuggestionKind, val onClick: (() -> Unit)? = null) {
        override fun toString() = text
    }

    /** The web's suggestion list under each field: Stadia first, then "Search all places" for the full search. */
    private inner class PlaceSearch(private val row: Row) {
        private var timer: Job? = null
        private var sequence = 0
        private var fullText: String? = null // text whose full results are shown
        private var suggestions = listOf<Suggestion>()
        private val popup = ListPopupWindow(requireContext()).apply {
            anchorView = row.field
            width = ListPopupWindow.MATCH_PARENT
            isModal = false
            inputMethodMode = ListPopupWindow.INPUT_METHOD_NEEDED
            setOnItemClickListener { _, _, position, _ -> suggestions.getOrNull(position)?.onClick?.invoke() }
        }

        init {
            row.input.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    activeRow = row
                } else {
                    dismiss()
                    // Any other text not picked from the list is dropped, so the field shows the point that will be planned
                    if (!useCoordinates()) setInputText(row, pointText(row.point))
                }
            }
            row.input.setOnEditorActionListener { _, actionId, event ->
                val enter = actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (!enter) return@setOnEditorActionListener false
                if (useCoordinates()) return@setOnEditorActionListener true
                // First search key: search all places. Again on those results: pick the first one
                val first = suggestions.firstOrNull { it.kind == SuggestionKind.RESULT }
                if (fullText == row.input.text.toString().trim() && popup.isShowing && first != null) first.onClick?.invoke()
                else searchAll()
                true
            }
            row.input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (settingText) return
                    timer?.cancel()
                    fullText = null
                    val text = s.toString().trim()
                    if (text.length < SEARCH_MIN_CHARS) return dismiss()
                    // Coordinates need no search: offer them as the only result
                    parseCoordinates(text)?.let {
                        render(listOf(GeocodeResult(formatCoordinates(it), it.lat, it.lng)), full = true)
                        return
                    }
                    timer = viewLifecycleOwner.lifecycleScope.launch {
                        delay(SEARCH_DEBOUNCE_MS)
                        search(text, full = false)
                    }
                }
            })
        }

        fun dismiss() {
            timer?.cancel()
            popup.dismiss()
        }

        private fun show(items: List<Suggestion>) {
            suggestions = items
            popup.setAdapter(SuggestionAdapter(items))
            if (row.input.hasFocus()) popup.show()
        }

        private fun render(results: List<GeocodeResult>, full: Boolean) {
            val items = results.map { result ->
                Suggestion(result.label, SuggestionKind.RESULT) {
                    dismiss()
                    setPoint(row, PlanPoint(result.lat, result.lng, result.label), fly = true)
                }
            }.ifEmpty { listOf(Suggestion(getString(R.string.plan_no_results), SuggestionKind.MESSAGE)) }
            // Stadia misses small villages: offer the slower full search
            show(if (full) items else items + Suggestion(getString(R.string.plan_search_all), SuggestionKind.SEARCH_ALL) { searchAll() })
        }

        private fun renderMessage(text: String) = show(listOf(Suggestion(text, SuggestionKind.MESSAGE)))

        private fun search(text: String, full: Boolean) {
            val current = ++sequence
            val center = mapView.mapCenter
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val response = routeService.geocode(
                        text,
                        String.format(Locale.US, "%.4f", center.latitude),
                        String.format(Locale.US, "%.4f", center.longitude),
                        if (full) "1" else null
                    )
                    if (current != sequence || !row.input.hasFocus()) return@launch
                    if (response.isSuccessful) {
                        fullText = if (full) text else null
                        render(response.body().orEmpty(), full)
                    } else {
                        renderMessage(response.errorBody()?.string()?.takeIf { it.isNotBlank() } ?: getString(R.string.error_generic))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (current == sequence && row.input.hasFocus()) renderMessage(getString(R.string.error_generic))
                }
            }
        }

        private fun searchAll() {
            val text = row.input.text.toString().trim()
            if (text.length < SEARCH_MIN_CHARS) return
            timer?.cancel()
            renderMessage(getString(R.string.plan_searching_all))
            search(text, full = true)
        }

        /** Typed coordinates become the point. false when the text isn't coordinates. */
        private fun useCoordinates(): Boolean {
            val text = row.input.text.toString().trim()
            val coordinates = parseCoordinates(text) ?: return false
            dismiss()
            // Unchanged text (e.g. the coordinates of a map tap): keep the point and the planned route
            if (text != pointText(row.point)) setPoint(row, coordinates, fly = true)
            return true
        }
    }

    private inner class SuggestionAdapter(private val items: List<Suggestion>) :
        ArrayAdapter<Suggestion>(requireContext(), android.R.layout.simple_list_item_1, items) {

        override fun isEnabled(position: Int) = items[position].kind != SuggestionKind.MESSAGE

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent) as TextView
            val item = items[position]
            view.textSize = if (item.kind == SuggestionKind.SEARCH_ALL) 14f else 15f
            view.setTypeface(null, if (item.kind == SuggestionKind.SEARCH_ALL) Typeface.BOLD else Typeface.NORMAL)
            when (item.kind) {
                SuggestionKind.RESULT -> view.setTextColor(MaterialColors.getColor(view, android.R.attr.textColorPrimary))
                SuggestionKind.MESSAGE -> view.setTextColor(mutedText)
                SuggestionKind.SEARCH_ALL -> view.setTextColor(ContextCompat.getColor(context, R.color.primary_500))
            }
            return view
        }
    }
    // #endregion

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            myLocationOverlay.enableMyLocation()
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        myLocationOverlay.disableMyLocation()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cancelPlanning()
        planning = false
        compassAnimator?.cancel()
        rows.forEach {
            it.search.dismiss()
            it.marker = null
        }
        routeLine = null
        connectorLines.clear()
        mapCenter = GeoPoint(mapView.mapCenter.latitude, mapView.mapCenter.longitude)
        mapZoom = mapView.zoomLevelDouble
        mapOrientation = mapView.mapOrientation
        mapView.onDetach()
    }
}

private fun ValueAnimator.doOnEnd(action: () -> Unit) {
    addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = action()
    })
}

private fun formatCoordinates(point: PlanPoint) = String.format(Locale.US, "%.5f, %.5f", point.lat, point.lng)

private fun pointText(point: PlanPoint?): String = point?.let { it.label ?: formatCoordinates(it) } ?: ""

/** "44.80123, 10.32876" as copied from a map (lat first), also "44.8 10.3" and the decimal comma "44,8; 10,3".
 * null when the text isn't a pair of valid coordinates. */
private fun parseCoordinates(text: String): PlanPoint? {
    val match = Regex("""^(-?\d+(?:\.\d+)?)\s*[,;\s]\s*(-?\d+(?:\.\d+)?)$""").find(text)
        ?: Regex("""^(-?\d+(?:,\d+)?)\s*[;\s]\s*(-?\d+(?:,\d+)?)$""").find(text)
        ?: return null
    val lat = match.groupValues[1].replace(",", ".").toDouble()
    val lng = match.groupValues[2].replace(",", ".").toDouble()
    if (abs(lat) > 90 || abs(lng) > 180) return null
    return PlanPoint(lat, lng)
}

private fun haversineMeters(a: PlanPoint, b: PlanPoint): Double {
    val rad = { deg: Double -> Math.toRadians(deg) }
    val h = sin(rad(b.lat - a.lat) / 2).pow(2) +
        cos(rad(a.lat)) * cos(rad(b.lat)) * sin(rad(b.lng - a.lng) / 2).pow(2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(h))
}

/** English grouping, as the web's Intl.NumberFormat("en"), with up to one decimal (exactly one with minDecimals = 1). */
private fun formatNumber(value: Double, minDecimals: Int): String =
    DecimalFormat(if (minDecimals == 1) "#,##0.0" else "#,##0.#", DecimalFormatSymbols(Locale.ENGLISH))
        .apply { roundingMode = RoundingMode.HALF_UP }
        .format(value)

private fun formatDistance(meters: Double) = "${formatNumber(meters / 1000, 1)} km"

private fun formatDuration(seconds: Double): String {
    val minutes = max(1, (seconds / 60).roundToInt())
    if (minutes < 60) return "$minutes min"
    return "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')} min"
}
