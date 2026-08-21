import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const EnduranceApp());
}

class EnduranceApp extends StatelessWidget {
  const EnduranceApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Battery Endurance',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.light,
        scaffoldBackgroundColor: Colors.white,
        colorScheme: const ColorScheme.light(
          primary: Colors.black,
          onPrimary: Colors.white,
          surface: Colors.white,
          onSurface: Colors.black,
        ),
        textTheme: const TextTheme(
          headlineLarge: TextStyle(
            fontSize: 56,
            fontWeight: FontWeight.w300,
            letterSpacing: -2,
            color: Colors.black,
          ),
          titleSmall: TextStyle(
            fontSize: 12,
            letterSpacing: 2,
            fontWeight: FontWeight.w600,
            color: Colors.black54,
          ),
          bodyMedium: TextStyle(fontSize: 14, color: Colors.black54),
        ),
      ),
      home: const EndurancePage(),
    );
  }
}

class EndurancePage extends StatefulWidget {
  const EndurancePage({super.key});

  @override
  State<EndurancePage> createState() => _EndurancePageState();
}

class _EndurancePageState extends State<EndurancePage>
    with WidgetsBindingObserver {
  static const _channel = MethodChannel('com.example.battery_stats/battery');
  static const _events = EventChannel('com.example.battery_stats/battery/events');

  Map<String, dynamic> _setup = {};
  Map<String, dynamic> _snapshot = {};
  List<Map<String, dynamic>> _days = [];
  Map<String, dynamic> _week = {};
  String _todayKey = '';
  int _selectedDayIndex = 6;
  bool _loading = true;
  StreamSubscription<dynamic>? _batterySub;
  Timer? _snapshotPoll;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _batterySub = _events.receiveBroadcastStream().listen(_onBatteryEvent);
    _snapshotPoll = Timer.periodic(const Duration(seconds: 2), (_) {
      _pollSnapshot();
    });
    _refresh();
  }

  @override
  void dispose() {
    _snapshotPoll?.cancel();
    _batterySub?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refresh();
    }
  }

  void _onBatteryEvent(dynamic event) {
    if (!mounted || event is! Map) return;
    setState(() {
      _snapshot = Map<String, dynamic>.from(event);
    });
  }

  Future<void> _pollSnapshot() async {
    if (!mounted || _loading) return;
    try {
      final snapshot =
          Map<String, dynamic>.from(await _channel.invokeMethod('getSnapshot'));
      if (!mounted) return;
      setState(() => _snapshot = snapshot);
    } on PlatformException {
      // Keep the last known snapshot.
    }
  }

  Future<void> _refresh() async {
    setState(() => _loading = true);
    try {
      final setup =
          Map<String, dynamic>.from(await _channel.invokeMethod('getSetupStatus'));
      final metrics =
          Map<String, dynamic>.from(await _channel.invokeMethod('getDayMetrics'));

      if (setup['ready'] == true && setup['collectorRunning'] != true) {
        await _channel.invokeMethod('startCollector');
      }

      final setupAfter =
          Map<String, dynamic>.from(await _channel.invokeMethod('getSetupStatus'));
      final snapshotAfter =
          Map<String, dynamic>.from(await _channel.invokeMethod('getSnapshot'));

      final daysRaw = metrics['days'] as List<dynamic>? ?? [];
      final days = daysRaw
          .map((d) => Map<String, dynamic>.from(d as Map))
          .toList();

      if (!mounted) return;
      setState(() {
        _setup = setupAfter;
        _snapshot = snapshotAfter;
        _days = days;
        _week = Map<String, dynamic>.from(metrics['week'] as Map? ?? {});
        _todayKey = metrics['todayKey'] as String? ?? '';
        if (_days.isEmpty) {
          _selectedDayIndex = 0;
        } else if (_selectedDayIndex >= days.length) {
          _selectedDayIndex = days.length - 1;
        }
        _loading = false;
      });
    } on PlatformException catch (e) {
      if (!mounted) return;
      setState(() => _loading = false);
      debugPrint('Platform error: ${e.message}');
    }
  }

  Future<void> _requestSetup(String type) async {
    await _channel.invokeMethod('requestSetup', {'type': type});
    if (type == 'autostart_ack') {
      await _refresh();
    }
  }

  Map<String, dynamic>? get _selectedDay {
    if (_days.isEmpty || _selectedDayIndex < 0 || _selectedDayIndex >= _days.length) {
      return null;
    }
    return _days[_selectedDayIndex];
  }

  String _formatHours(double? hours) {
    if (hours == null) return '—';
    return '${hours.toStringAsFixed(1)} h';
  }

  String _formatSubtitle(Map<String, dynamic>? metrics) {
    if (metrics == null) return 'no data';
    final screenOn = (metrics['screenOnHours'] as num?)?.toDouble() ?? 0;
    final mah = (metrics['drainMah'] as num?)?.toDouble() ?? 0;
    final steps = metrics['stepCount'] as int? ?? 0;
    if (steps == 0) return 'no µAh drain steps yet';
    return '${screenOn.toStringAsFixed(1)} h on · ${mah.toStringAsFixed(0)} mAh · $steps steps';
  }

  String _coverageNote(Map<String, dynamic>? metrics) {
    if (metrics == null) return '';
    final gaps = metrics['gapCount'] as int? ?? 0;
    final gapH = (metrics['droppedGapHours'] as num?)?.toDouble() ?? 0;
    if (gaps <= 0) return '';
    return 'log has $gaps hole${gaps == 1 ? '' : 's'} (${gapH.toStringAsFixed(1)} h not counted)';
  }

  String _lastSampleAge() {
    final ts = (_snapshot['lastLogTimestampMs'] as num?)?.toInt() ?? 0;
    if (ts <= 0) return '';
    final age = DateTime.now().difference(DateTime.fromMillisecondsSinceEpoch(ts));
    if (age.inMinutes < 2) return 'last sample just now';
    if (age.inHours < 1) return 'last sample ${age.inMinutes} min ago';
    if (age.inHours < 48) return 'last sample ${age.inHours} h ago';
    return 'last sample ${age.inDays} d ago';
  }

  String _dayLabel(String dateKey) {
    try {
      final date = DateFormat('yyyy-MM-dd').parse(dateKey);
      return DateFormat('EEE').format(date).substring(0, 1).toUpperCase();
    } catch (_) {
      return '?';
    }
  }

  String _selectedTitle() {
    final day = _selectedDay;
    if (day == null) return 'TODAY';
    final key = day['dateKey'] as String? ?? '';
    if (key == _todayKey) return 'TODAY';
    try {
      return DateFormat('EEE d MMM').format(DateFormat('yyyy-MM-dd').parse(key)).toUpperCase();
    } catch (_) {
      return key.toUpperCase();
    }
  }

  @override
  Widget build(BuildContext context) {
    final ready = _setup['ready'] == true;
    final selected = _selectedDay;
    final selectedSot = selected != null && selected['hasEnoughData'] == true
        ? (selected['sotHoursPer100'] as num?)?.toDouble()
        : null;
    final weekSot = _week['hasEnoughData'] == true
        ? (_week['sotHoursPer100'] as num?)?.toDouble()
        : null;

    final level = _snapshot['level'] as int? ?? -1;
    final plugged = _snapshot['plugged'] == true;
    final charging = _snapshot['charging'] == true;
    final collecting = _setup['collectorRunning'] == true;
    final samplingPaused = ready && !collecting;

    return Scaffold(
      body: SafeArea(
        child: _loading
            ? const Center(child: CircularProgressIndicator(color: Colors.black))
            : Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Padding(
                    padding: const EdgeInsets.fromLTRB(24, 16, 24, 0),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          level >= 0 ? '$level%' : '—',
                          style: Theme.of(context).textTheme.titleSmall,
                        ),
                        Text(
                          (plugged || charging) ? 'CHARGING' : 'UNPLUGGED',
                          style: Theme.of(context).textTheme.titleSmall,
                        ),
                      ],
                    ),
                  ),
                  if (!ready) ...[
                    const SizedBox(height: 24),
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 24),
                      child: _SetupPanel(
                        setup: _setup,
                        onRequest: _requestSetup,
                        onRefresh: _refresh,
                      ),
                    ),
                  ] else ...[
                    const Spacer(flex: 2),
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 24),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(_selectedTitle(), style: Theme.of(context).textTheme.titleSmall),
                          const SizedBox(height: 8),
                          Text(
                            _formatHours(selectedSot),
                            style: Theme.of(context).textTheme.headlineLarge,
                          ),
                          const SizedBox(height: 8),
                          Text(
                            _formatSubtitle(selected),
                            style: Theme.of(context).textTheme.bodyMedium,
                          ),
                          if (selected != null && selected['hasEnoughData'] != true)
                            Padding(
                              padding: const EdgeInsets.only(top: 4),
                              child: Text(
                                selectedSot == null ? 'need ≥3% screen-on drain (µAh)' : '',
                                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                                      fontSize: 12,
                                      color: Colors.black38,
                                    ),
                              ),
                            ),
                          if (_coverageNote(selected).isNotEmpty)
                            Padding(
                              padding: const EdgeInsets.only(top: 4),
                              child: Text(
                                _coverageNote(selected),
                                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                                      fontSize: 12,
                                      color: Colors.black38,
                                    ),
                              ),
                            ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 40),
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 24),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text('7 DAYS', style: Theme.of(context).textTheme.titleSmall),
                          const SizedBox(height: 8),
                          Text(
                            _formatHours(weekSot),
                            style: Theme.of(context).textTheme.headlineLarge?.copyWith(fontSize: 40),
                          ),
                          const SizedBox(height: 8),
                          Text(
                            _formatSubtitle(_week),
                            style: Theme.of(context).textTheme.bodyMedium,
                          ),
                          if (_coverageNote(_week).isNotEmpty)
                            Padding(
                              padding: const EdgeInsets.only(top: 4),
                              child: Text(
                                _coverageNote(_week),
                                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                                      fontSize: 12,
                                      color: Colors.black38,
                                    ),
                              ),
                            ),
                        ],
                      ),
                    ),
                    const Spacer(flex: 3),
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 8),
                      child: Column(
                        children: [
                          Text(
                            [
                              if (collecting) 'collecting',
                              if (samplingPaused) 'sampling paused',
                              if (_lastSampleAge().isNotEmpty) _lastSampleAge(),
                              if (_setup['batteryUnrestricted'] == true) 'unrestricted',
                              if (_setup['notificationsGranted'] == true) 'notifications',
                              if (_setup['autostartAcknowledged'] == true) 'autostart',
                            ].where((s) => s.isNotEmpty).join(' · '),
                            style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontSize: 12),
                            textAlign: TextAlign.center,
                          ),
                          if (collecting)
                            TextButton(
                              onPressed: () => _requestSetup('notification_channel'),
                              child: const Text(
                                'Hide sampling notification',
                                style: TextStyle(fontSize: 12, color: Colors.black54),
                              ),
                            ),
                        ],
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                      child: Row(
                        children: List.generate(_days.length, (index) {
                          final day = _days[index];
                          final key = day['dateKey'] as String? ?? '';
                          final hasData = (day['stepCount'] as int? ?? 0) > 0;
                          final selectedNow = index == _selectedDayIndex;
                          return Expanded(
                            child: GestureDetector(
                              onTap: () => setState(() => _selectedDayIndex = index),
                              child: Container(
                                margin: const EdgeInsets.symmetric(horizontal: 2),
                                padding: const EdgeInsets.symmetric(vertical: 12),
                                decoration: BoxDecoration(
                                  border: Border(
                                    top: BorderSide(
                                      color: selectedNow ? Colors.black : Colors.black12,
                                      width: selectedNow ? 2 : 1,
                                    ),
                                  ),
                                ),
                                child: Column(
                                  children: [
                                    Text(
                                      _dayLabel(key),
                                      style: TextStyle(
                                        fontSize: 12,
                                        fontWeight: selectedNow ? FontWeight.w700 : FontWeight.w400,
                                        color: hasData ? Colors.black : Colors.black26,
                                      ),
                                    ),
                                    if (hasData)
                                      Container(
                                        margin: const EdgeInsets.only(top: 6),
                                        width: 4,
                                        height: 4,
                                        decoration: const BoxDecoration(
                                          color: Colors.black,
                                          shape: BoxShape.circle,
                                        ),
                                      ),
                                  ],
                                ),
                              ),
                            ),
                          );
                        }),
                      ),
                    ),
                  ],
                ],
              ),
      ),
    );
  }
}

class _SetupPanel extends StatelessWidget {
  const _SetupPanel({
    required this.setup,
    required this.onRequest,
    required this.onRefresh,
  });

  final Map<String, dynamic> setup;
  final Future<void> Function(String type) onRequest;
  final Future<void> Function() onRefresh;

  @override
  Widget build(BuildContext context) {
    final notifications = setup['notificationsGranted'] == true;
    final battery = setup['batteryUnrestricted'] == true;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('SETUP', style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: 16),
        _SetupRow(
          label: 'Notifications',
          done: notifications,
          onTap: () => onRequest('notifications'),
        ),
        const SizedBox(height: 12),
        _SetupRow(
          label: 'Unrestricted battery',
          done: battery,
          onTap: () => onRequest('battery_optimization'),
        ),
        const SizedBox(height: 12),
        _SetupRow(
          label: 'Autostart (ColorOS)',
          done: setup['autostartAcknowledged'] == true,
          onTap: () => onRequest('autostart'),
        ),
        if (setup['autostartAcknowledged'] != true) ...[
          const SizedBox(height: 4),
          TextButton(
            onPressed: () => onRequest('autostart_ack'),
            child: const Text('I enabled autostart'),
          ),
        ],
        if (!battery) ...[
          const SizedBox(height: 8),
          TextButton(
            onPressed: () => onRequest('battery_settings'),
            child: const Text('Open app battery settings'),
          ),
        ],
        const SizedBox(height: 24),
        OutlinedButton(
          onPressed: onRefresh,
          child: const Text('Refresh'),
        ),
      ],
    );
  }
}

class _SetupRow extends StatelessWidget {
  const _SetupRow({
    required this.label,
    required this.done,
    required this.onTap,
  });

  final String label;
  final bool done;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: done ? null : onTap,
      child: Row(
        children: [
          Icon(
            done ? Icons.check : Icons.circle_outlined,
            size: 18,
            color: done ? Colors.black : Colors.black38,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              label,
              style: TextStyle(
                color: done ? Colors.black : Colors.black87,
                decoration: done ? TextDecoration.lineThrough : null,
              ),
            ),
          ),
          if (!done)
            const Text(
              'Grant',
              style: TextStyle(fontSize: 12, color: Colors.black54),
            ),
        ],
      ),
    );
  }
}
