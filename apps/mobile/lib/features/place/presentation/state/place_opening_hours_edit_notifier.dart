import 'package:flutter/foundation.dart';
import '../../../../core/error/api_exception.dart';
import '../../domain/entities/place_commercial_entities.dart';
import '../../domain/repositories/place_commercial_repository.dart';

/// Notifier para edição dos horários de funcionamento semanais de um estabelecimento reivindicado (F2.1).
class PlaceOpeningHoursEditNotifier extends ChangeNotifier {
  final PlaceCommercialRepository repository;

  bool _isLoading = false;
  bool _isSaving = false;
  String? _errorMessage;
  String? _successMessage;
  List<DaySchedule> _days = [];
  bool _configured = false;
  bool _disposed = false;

  PlaceOpeningHoursEditNotifier({required this.repository});

  bool get isLoading => _isLoading;
  bool get isSaving => _isSaving;
  String? get errorMessage => _errorMessage;
  String? get successMessage => _successMessage;
  List<DaySchedule> get days => List.unmodifiable(_days);
  bool get configured => _configured;

  static final RegExp _timeRegex = RegExp(r'^([01]\d|2[0-3]):[0-5]\d$');

  /// Carrega os horários atuais do estabelecimento e garante os 7 dias da semana ordenados.
  Future<void> load(String placeId) async {
    if (_isLoading) return;
    _isLoading = true;
    _errorMessage = null;
    _successMessage = null;
    _notifySafely();

    try {
      final result = await repository.getOpeningHours(placeId);
      _configured = result.configured;
      _days = _normalizeDays(result.days);
    } catch (e) {
      _errorMessage = e is ApiException
          ? e.detail
          : 'Falha ao carregar horários de funcionamento do estabelecimento.';
      if (_days.isEmpty) {
        _days = _defaultWeek();
      }
    } finally {
      _isLoading = false;
      _notifySafely();
    }
  }

  /// Alterna o estado de aberto/fechado de um dia específico (1..7).
  void setDayClosed(int dayOfWeek, bool closed) {
    final index = _days.indexWhere((d) => d.dayOfWeek == dayOfWeek);
    if (index == -1) return;

    final current = _days[index];
    List<TimeInterval> intervals = current.intervals;

    if (closed) {
      intervals = const [];
    } else if (intervals.isEmpty) {
      intervals = const [TimeInterval(openTime: '08:00', closeTime: '18:00')];
    }

    _days[index] = current.copyWith(closed: closed, intervals: intervals);
    _errorMessage = null;
    _notifySafely();
  }

  /// Adiciona um novo intervalo de funcionamento ao dia.
  void addInterval(int dayOfWeek, {String openTime = '08:00', String closeTime = '18:00'}) {
    final index = _days.indexWhere((d) => d.dayOfWeek == dayOfWeek);
    if (index == -1) return;

    final current = _days[index];
    final updatedIntervals = List<TimeInterval>.from(current.intervals)
      ..add(TimeInterval(openTime: openTime, closeTime: closeTime));

    _days[index] = current.copyWith(closed: false, intervals: updatedIntervals);
    _errorMessage = null;
    _notifySafely();
  }

  /// Remove o intervalo de funcionamento no índice especificado.
  void removeInterval(int dayOfWeek, int intervalIndex) {
    final index = _days.indexWhere((d) => d.dayOfWeek == dayOfWeek);
    if (index == -1) return;

    final current = _days[index];
    if (intervalIndex < 0 || intervalIndex >= current.intervals.length) return;

    final updatedIntervals = List<TimeInterval>.from(current.intervals)
      ..removeAt(intervalIndex);

    _days[index] = current.copyWith(
      closed: updatedIntervals.isEmpty ? true : current.closed,
      intervals: updatedIntervals,
    );
    _errorMessage = null;
    _notifySafely();
  }

  /// Atualiza os horários de início e/ou término de um intervalo existente.
  void updateInterval(
    int dayOfWeek,
    int intervalIndex, {
    String? openTime,
    String? closeTime,
  }) {
    final index = _days.indexWhere((d) => d.dayOfWeek == dayOfWeek);
    if (index == -1) return;

    final current = _days[index];
    if (intervalIndex < 0 || intervalIndex >= current.intervals.length) return;

    final oldInterval = current.intervals[intervalIndex];
    final updatedInterval = oldInterval.copyWith(
      openTime: openTime ?? oldInterval.openTime,
      closeTime: closeTime ?? oldInterval.closeTime,
    );

    final updatedIntervals = List<TimeInterval>.from(current.intervals);
    updatedIntervals[intervalIndex] = updatedInterval;

    _days[index] = current.copyWith(intervals: updatedIntervals);
    _errorMessage = null;
    _notifySafely();
  }

  /// Executa a validação local das regras de horário de funcionamento.
  /// Retorna a mensagem de erro da primeira inconsistência encontrada, ou `null` se válido.
  String? validate() {
    for (final day in _days) {
      if (day.closed) {
        if (day.intervals.isNotEmpty) {
          return 'O dia ${day.localizedDayName} está marcado como fechado e não deve conter intervalos.';
        }
        continue;
      }

      if (day.intervals.isEmpty) {
        return 'O dia ${day.localizedDayName} está aberto mas não possui nenhum horário de funcionamento configurado.';
      }

      for (final interval in day.intervals) {
        if (!_timeRegex.hasMatch(interval.openTime) || !_timeRegex.hasMatch(interval.closeTime)) {
          return 'No dia ${day.localizedDayName}, os horários devem estar no formato HH:mm válido.';
        }
        if (!_isTimeStrictlyBefore(interval.openTime, interval.closeTime)) {
          return 'No dia ${day.localizedDayName}, o horário de abertura (${interval.openTime}) deve ser anterior ao de fechamento (${interval.closeTime}).';
        }
      }

      final sorted = List<TimeInterval>.from(day.intervals)
        ..sort((a, b) => a.openTime.compareTo(b.openTime));

      for (int i = 0; i < sorted.length - 1; i++) {
        if (sorted[i].overlapsWith(sorted[i + 1])) {
          return 'No dia ${day.localizedDayName}, existem horários de funcionamento sobrepostos (${sorted[i].formatted} e ${sorted[i + 1].formatted}).';
        }
      }
    }
    return null;
  }

  /// Salva as alterações de horários no backend.
  /// Retorna `true` em caso de sucesso ou `false` se houver erro de validação ou de rede.
  Future<bool> save({
    required String placeId,
    required String businessAccountId,
  }) async {
    if (_isSaving) return false;

    _errorMessage = null;
    _successMessage = null;

    final validationError = validate();
    if (validationError != null) {
      _errorMessage = validationError;
      _notifySafely();
      return false;
    }

    _isSaving = true;
    _notifySafely();

    try {
      final updated = await repository.updateOpeningHours(
        placeId: placeId,
        businessAccountId: businessAccountId,
        days: _days,
      );
      _configured = updated.configured;
      _days = _normalizeDays(updated.days);
      _successMessage = 'Horários de funcionamento atualizados com sucesso.';
      return true;
    } catch (e) {
      _errorMessage = e is ApiException
          ? e.detail
          : 'Falha ao salvar horários de funcionamento.';
      return false;
    } finally {
      _isSaving = false;
      _notifySafely();
    }
  }

  static bool _isTimeStrictlyBefore(String t1, String t2) {
    try {
      final p1 = t1.split(':').map(int.parse).toList();
      final p2 = t2.split(':').map(int.parse).toList();
      if (p1[0] != p2[0]) return p1[0] < p2[0];
      return p1[1] < p2[1];
    } catch (_) {
      return false;
    }
  }

  static List<DaySchedule> _normalizeDays(List<DaySchedule> rawDays) {
    final Map<int, DaySchedule> dayMap = {
      for (final d in rawDays) d.dayOfWeek: d,
    };

    final List<DaySchedule> result = [];
    for (int dayOfWeek = 1; dayOfWeek <= 7; dayOfWeek++) {
      if (dayMap.containsKey(dayOfWeek)) {
        final existing = dayMap[dayOfWeek]!;
        result.add(existing.copyWith(
          intervals: existing.closed ? const [] : existing.intervals,
        ));
      } else {
        result.add(DaySchedule(
          dayOfWeek: dayOfWeek,
          dayName: _defaultDayName(dayOfWeek),
          closed: true,
          intervals: const [],
        ));
      }
    }
    result.sort((a, b) => a.dayOfWeek.compareTo(b.dayOfWeek));
    return result;
  }

  static List<DaySchedule> _defaultWeek() {
    return List.generate(
      7,
      (index) => DaySchedule(
        dayOfWeek: index + 1,
        dayName: _defaultDayName(index + 1),
        closed: true,
        intervals: const [],
      ),
    );
  }

  static String _defaultDayName(int dayOfWeek) {
    switch (dayOfWeek) {
      case 1:
        return 'MONDAY';
      case 2:
        return 'TUESDAY';
      case 3:
        return 'WEDNESDAY';
      case 4:
        return 'THURSDAY';
      case 5:
        return 'FRIDAY';
      case 6:
        return 'SATURDAY';
      case 7:
        return 'SUNDAY';
      default:
        return '';
    }
  }

  void _notifySafely() {
    if (!_disposed) {
      notifyListeners();
    }
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
