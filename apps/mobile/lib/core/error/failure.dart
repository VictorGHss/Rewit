abstract class Failure {
  final String message;

  const Failure(this.message);

  @override
  String toString() => '$runtimeType: $message';
}

class ServerFailure extends Failure {
  final int? statusCode;
  final String? code;

  const ServerFailure(super.message, {this.statusCode, this.code});
}

class NetworkFailure extends Failure {
  const NetworkFailure(super.message);
}

class AuthFailure extends Failure {
  final String? code;

  const AuthFailure(super.message, {this.code});
}

class ValidationFailure extends Failure {
  final Map<String, dynamic>? invalidParams;

  const ValidationFailure(super.message, {this.invalidParams});
}
