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
      title: 'edrc',
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
  static const _channel = MethodChannel('com.saplin.edrc/battery');
  static const _events = EventChannel('com.saplin.edrc/battery/events');
  static const _shizukuEvents =
      EventChannel('com.saplin.edrc/battery/shizuku');

  Map<String, dynamic> _setup = {};
  Map<String, dynamic> _snapshot = {};
  Map<String, dynamic>? _cycle;
  List<Map<String, dynamic>> _past = [];
  List<Map<String, dynamic>> _intervals = [];
  int? _updatedAtMs;
  bool _idleMode = false;
  bool _loading = true;
  String? _error;
  int _refreshGen = 0;
  StreamSubscription<dynamic>? _batterySub;
  StreamSubscription<dynamic>? _shizukuSub;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _batterySub = _events.receiveBroadcastStream().listen(_onBatteryEvent);
    _shizukuSub = _shizukuEvents.receiveBroadcastStream().listen(_onShizukuStatus);
    _refresh();
  }

  @override
  void dispose() {
    _batterySub?.cancel();
    _shizukuSub?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refresh(silent: true);
    }
  }

  void _onBatteryEvent(dynamic event) {
    if (!mounted || event is! Map) return;
    setState(() => _snapshot = Map<String, dynamic>.from(event));
  }

  void _onShizukuStatus(dynamic event) {
    if (!mounted || event is! Map) return;
    final setup = Map<String, dynamic>.from(event);
    final becameReady = setup['ready'] == true && _setup['ready'] != true;
    setState(() => _setup = setup);
    if (becameReady) {
      _refresh();
    }
  }

  Future<void> _refresh({bool force = false, bool silent = false}) async {
    final gen = ++_refreshGen;
    if (!silent) {
      setState(() {
        _loading = true;
        _error = null;
      });
    }
    try {
      final setup =
          Map<String, dynamic>.from(await _channel.invokeMethod('getSetupStatus'));
      final snapshot =
          Map<String, dynamic>.from(await _channel.invokeMethod('getSnapshot'));

      Map<String, dynamic>? cycle = _cycle;
      var past = _past;
      var intervals = _intervals;
      int? updatedAtMs = _updatedAtMs;

      if (setup['ready'] == true) {
        final metrics = Map<String, dynamic>.from(
          await _channel.invokeMethod('getMetrics', {'force': force}),
        );
        cycle = _asMap(metrics['cycle']);
        updatedAtMs = (metrics['updatedAtMs'] as num?)?.toInt();
        past = (metrics['past'] as List<dynamic>? ?? [])
            .map((d) => Map<String, dynamic>.from(d as Map))
            .toList();
        intervals = (metrics['intervals'] as List<dynamic>? ?? [])
            .map((d) => Map<String, dynamic>.from(d as Map))
            .toList();
      }

      if (!mounted || gen != _refreshGen) return;
      setState(() {
        _setup = setup;
        _snapshot = snapshot;
        _cycle = cycle;
        _past = past;
        _intervals = intervals;
        _updatedAtMs = updatedAtMs;
        _loading = false;
      });
    } catch (e) {
      if (!mounted || gen != _refreshGen) return;
      setState(() {
        _loading = false;
        _error = e is PlatformException ? (e.message ?? e.code) : e.toString();
      });
    }
  }

  Map<String, dynamic>? _asMap(dynamic value) {
    if (value is Map) return Map<String, dynamic>.from(value);
    return null;
  }

  Future<void> _requestSetup(String type) async {
    await _channel.invokeMethod('requestSetup', {'type': type});
  }

  String _formatHours(double? hours) {
    if (hours == null) return '—';
    return '${hours.toStringAsFixed(1)} h';
  }

  DateTime? _parseClock(String? raw) {
    if (raw == null || raw.isEmpty) return null;
    try {
      return DateFormat('yyyy-MM-dd-HH-mm-ss').parse(raw);
    } catch (_) {
      return null;
    }
  }

  String _sincePhrase(String? raw) {
    final dt = _parseClock(raw);
    if (dt == null) return '';
    final now = DateTime.now();
    final time = DateFormat('HH:mm').format(dt);
    final today = DateTime(now.year, now.month, now.day);
    final that = DateTime(dt.year, dt.month, dt.day);
    final days = today.difference(that).inDays;
    if (days == 0) return 'today $time';
    if (days == 1) return 'yesterday $time';
    return DateFormat('d MMM, HH:mm').format(dt);
  }

  String _dayPhrase(String? raw) {
    final dt = _parseClock(raw);
    if (dt == null) return '';
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final that = DateTime(dt.year, dt.month, dt.day);
    final days = today.difference(that).inDays;
    if (days == 0) return 'today';
    if (days == 1) return 'yesterday';
    return DateFormat('EEE d MMM').format(dt);
  }

  String _formatTime(int? ms) {
    if (ms == null || ms <= 0) return '';
    return DateFormat('HH:mm').format(DateTime.fromMillisecondsSinceEpoch(ms));
  }

  String _onSinceLine(Map<String, dynamic>? metrics, {required bool idle}) {
    if (metrics == null) return '';
    final hours = idle
        ? ((metrics['screenOffHours'] as num?)?.toDouble() ?? 0)
        : ((metrics['screenOnHours'] as num?)?.toDouble() ?? 0);
    final since = _sincePhrase(metrics['startClock'] as String?);
    final kind = idle ? 'idle' : 'screen on';
    final value = hours.toStringAsFixed(1);
    final core = since.isEmpty ? '$value h $kind' : '$value h $kind since $since';
    return '$core, on battery';
  }

  String _caption({required bool idle, required bool enough}) {
    if (!enough) {
      return idle ? 'Need more idle drain on battery' : 'Need more drain on battery';
    }
    return idle ? 'idle per full charge' : 'screen on per full charge';
  }

  @override
  Widget build(BuildContext context) {
    final ready = _setup['ready'] == true;
    final cycleSot = _cycle != null && _cycle!['hasEnoughData'] == true
        ? (_cycle!['sotHoursPer100'] as num?)?.toDouble()
        : null;
    final cycleIdle = _cycle != null && _cycle!['hasEnoughIdle'] == true
        ? (_cycle!['idleHoursPer100'] as num?)?.toDouble()
        : null;
    final headline = _idleMode ? cycleIdle : cycleSot;
    final onSince = _onSinceLine(_cycle, idle: _idleMode);
    final enough = headline != null;
    final caption = _error ??
        (_cycle == null
            ? 'No batterystats yet'
            : _caption(idle: _idleMode, enough: enough));
    final level = _snapshot['level'] as int? ?? -1;
    final plugged = _snapshot['plugged'] == true;
    final charging = _snapshot['charging'] == true;
    final updated = _formatTime(_updatedAtMs);

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
                        onRefresh: () => _refresh(force: true),
                      ),
                    ),
                    if (_error != null)
                      Padding(
                        padding: const EdgeInsets.fromLTRB(24, 16, 24, 0),
                        child: Text(
                          _error!,
                          style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                                fontSize: 12,
                                color: Colors.black54,
                              ),
                        ),
                      ),
                  ] else ...[
                    const Spacer(flex: 2),
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 24),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          GestureDetector(
                            onTap: () => setState(() => _idleMode = !_idleMode),
                            child: Text(
                              _formatHours(headline),
                              style: Theme.of(context).textTheme.headlineLarge,
                            ),
                          ),
                          const SizedBox(height: 8),
                          Text(caption, style: Theme.of(context).textTheme.bodyMedium),
                          if (onSince.isNotEmpty) ...[
                            const SizedBox(height: 6),
                            Text(onSince, style: Theme.of(context).textTheme.bodyMedium),
                          ],
                        ],
                      ),
                    ),
                    if (_past.isNotEmpty) ...[
                      const SizedBox(height: 40),
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 24),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('PREVIOUS', style: Theme.of(context).textTheme.titleSmall),
                            const SizedBox(height: 12),
                            ..._past.take(5).map((item) {
                              final sot = item['hasEnoughData'] == true
                                  ? (item['sotHoursPer100'] as num?)?.toDouble()
                                  : null;
                              final day = _dayPhrase(item['startClock'] as String?);
                              return Padding(
                                padding: const EdgeInsets.only(bottom: 8),
                                child: Row(
                                  children: [
                                    Expanded(
                                      child: Text(
                                        day,
                                        style: Theme.of(context).textTheme.bodyMedium,
                                      ),
                                    ),
                                    Text(
                                      _formatHours(sot),
                                      style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                                            color: Colors.black,
                                          ),
                                    ),
                                  ],
                                ),
                              );
                            }),
                          ],
                        ),
                      ),
                    ],
                    if (_intervals.isNotEmpty) ...[
                      const SizedBox(height: 32),
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 24),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('RECENT', style: Theme.of(context).textTheme.titleSmall),
                            const SizedBox(height: 12),
                            ..._intervals.take(4).map((interval) {
                              final from = _formatTime((interval['fromTsMs'] as num?)?.toInt());
                              final to = _formatTime((interval['toTsMs'] as num?)?.toInt());
                              final sot = (interval['sotHoursPer100'] as num?)?.toDouble();
                              return Padding(
                                padding: const EdgeInsets.only(bottom: 8),
                                child: Row(
                                  children: [
                                    Expanded(
                                      child: Text(
                                        '$from–$to',
                                        style: Theme.of(context).textTheme.bodyMedium,
                                      ),
                                    ),
                                    Text(
                                      _formatHours(sot),
                                      style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                                            color: Colors.black,
                                          ),
                                    ),
                                  ],
                                ),
                              );
                            }),
                          ],
                        ),
                      ),
                    ],
                    if (_error != null)
                      Padding(
                        padding: const EdgeInsets.fromLTRB(24, 12, 24, 0),
                        child: Text(
                          _error!,
                          style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                                fontSize: 12,
                                color: Colors.black54,
                              ),
                        ),
                      ),
                    const Spacer(flex: 3),
                    Padding(
                      padding: const EdgeInsets.fromLTRB(24, 0, 24, 16),
                      child: Column(
                        children: [
                          Text(
                            updated.isEmpty ? '' : 'updated $updated',
                            style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontSize: 12),
                          ),
                          TextButton(
                            onPressed: () => _refresh(force: true),
                            child: const Text(
                              'Refresh',
                              style: TextStyle(fontSize: 12, color: Colors.black54),
                            ),
                          ),
                        ],
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
    final installed = setup['installed'] == true;
    final running = setup['running'] == true;
    final permission = setup['permissionGranted'] == true;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('SETUP', style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: 16),
        _SetupRow(
          label: 'Shizuku installed',
          done: installed,
          action: 'Open',
          onTap: () => onRequest('shizuku_open'),
        ),
        const SizedBox(height: 12),
        _SetupRow(
          label: 'Shizuku running',
          done: running,
          action: 'Open',
          onTap: () => onRequest('shizuku_open'),
        ),
        const SizedBox(height: 12),
        _SetupRow(
          label: 'Shizuku permission',
          done: permission,
          action: 'Grant',
          onTap: () => onRequest('shizuku_permission'),
        ),
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
    required this.action,
    required this.onTap,
  });

  final String label;
  final bool done;
  final String action;
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
            Text(
              action,
              style: const TextStyle(fontSize: 12, color: Colors.black54),
            ),
        ],
      ),
    );
  }
}
