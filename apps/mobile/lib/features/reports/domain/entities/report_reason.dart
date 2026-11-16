/// Motivos padronizados para denúncia comunitária em conformidade com o backend Rewit.
enum ReportReason {
  spam('SPAM', 'Spam ou conteúdo comercial não solicitado'),
  harassment('HARASSMENT', 'Assédio, ameaça ou intimidação pessoal'),
  hateSpeech('HATE_SPEECH', 'Discurso de ódio ou discriminação'),
  misinformation('MISINFORMATION', 'Desinformação ou conteúdo comprovadamente enganoso'),
  inappropriateContent('INAPPROPRIATE_CONTENT', 'Conteúdo impróprio ou ofensivo à comunidade'),
  fraud('FRAUD', 'Fraude, golpe ou tentativa de enganar os usuários');

  final String backendValue;
  final String label;

  const ReportReason(this.backendValue, this.label);

  /// Converte o código textual retornado pelo backend na enumeração correspondente.
  static ReportReason fromBackendValue(String value) {
    final normalized = value.trim().toUpperCase();
    for (final reason in ReportReason.values) {
      if (reason.backendValue == normalized) {
        return reason;
      }
    }
    throw FormatException('Motivo de denúncia desconhecido: $value');
  }
}
