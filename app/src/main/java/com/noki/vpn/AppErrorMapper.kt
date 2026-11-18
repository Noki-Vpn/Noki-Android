package com.noki.vpn

import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.BackendException
import java.io.IOException

object AppErrorMapper {
    fun readableErrorType(error: Throwable): String {
        return when (error) {
            is BackendException -> "backend_${error.statusCode}"
            is IOException -> "network"
            else -> error::class.java.simpleName.ifBlank { "error" }
        }
    }

    fun readableAuthError(
        language: AppLanguage,
        error: Throwable,
    ): String {
        return when (error) {
            is BackendException -> when (error.statusCode) {
                401 -> tr(language, "Неверный e-mail / username или пароль", "Invalid email / username or password")
                422 -> tr(language, "Проверьте e-mail / username и пароль", "Check your email / username and password")
                else -> readableNetworkError(language, error)
            }

            else -> readableNetworkError(language, error)
        }
    }

    fun readableGoogleAuthError(
        language: AppLanguage,
        error: Throwable,
    ): String = when (error) {
        is BackendException -> when (error.statusCode) {
            401 -> tr(language, "Google не подтвердил вход", "Google could not verify this sign-in")
            409 -> tr(
                language,
                "Этот e-mail уже используется. Войдите по e-mail",
                "This email is already in use. Sign in with email",
            )
            else -> readableNetworkError(language, error)
        }
        else -> readableNetworkError(language, error)
    }

    fun readableTelegramAuthError(
        language: AppLanguage,
        error: Throwable,
    ): String = when (error) {
        is BackendException -> when (error.statusCode) {
            401 -> tr(
                language,
                "Не удалось подтвердить вход через Telegram",
                "Could not verify Telegram sign-in",
            )
            409 -> tr(
                language,
                "Эта ссылка Telegram уже использована. Попробуйте войти ещё раз",
                "This Telegram sign-in was already used. Please try again",
            )
            429 -> tr(
                language,
                "Слишком много попыток. Попробуйте позже",
                "Too many attempts. Try again later",
            )
            503 -> tr(
                language,
                "Вход через Telegram временно недоступен",
                "Telegram sign-in is temporarily unavailable",
            )
            else -> readableNetworkError(language, error)
        }
        is IOException -> tr(
            language,
            "Нет соединения с сервером",
            "Cannot reach the server",
        )
        else -> tr(
            language,
            "Не удалось войти через Telegram. Попробуйте ещё раз",
            "Could not sign in with Telegram. Please try again",
        )
    }

    fun readableTelegramSdkError(
        language: AppLanguage,
        code: String,
    ): String = when (code) {
        "callback_not_received" -> tr(
            language,
            "Telegram не завершил вход. Попробуйте ещё раз",
            "Telegram did not complete sign-in. Please try again",
        )
        "telegram_exchange_failed" -> tr(
            language,
            "Не удалось подтвердить ответ Telegram. Попробуйте ещё раз",
            "Could not verify the Telegram response. Please try again",
        )
        "missing_id_token", "invalid_callback" -> tr(
            language,
            "Не удалось получить данные от Telegram. Попробуйте ещё раз",
            "Could not get data from Telegram. Please try again",
        )
        else -> tr(
            language,
            "Не удалось открыть вход через Telegram. Попробуйте ещё раз",
            "Could not open Telegram sign-in. Please try again",
        )
    }

    fun readableRegistrationError(
        language: AppLanguage,
        error: Throwable,
    ): String {
        return when (error) {
            is BackendException -> when (error.statusCode) {
                409 -> when {
                    error.message == "Credentials setup is not required" ->
                        tr(language, "Регистрация уже завершена. Обновляем данные аккаунта", "Registration is already complete. Refreshing your account")

                    error.message.contains("email", ignoreCase = true) ->
                        tr(language, "Такой e-mail уже зарегистрирован", "This email is already registered")

                    error.message.contains("username", ignoreCase = true) ->
                        tr(language, "Такой username уже занят, попробуйте другой", "This username is taken, try another")

                    else ->
                        tr(language, "Не удалось завершить регистрацию: конфликт данных. Повторите попытку", "Registration could not be completed: conflicting data. Please try again")
                }
                422 -> readableRegistrationValidationError(language, error.message)
                else -> readableNetworkError(language, error)
            }

            else -> readableNetworkError(language, error)
        }
    }

    fun readableAccountSecurityError(
        language: AppLanguage,
        error: Throwable,
    ): String {
        return when (error) {
            is BackendException -> when {
                error.statusCode == 401 && error.message.contains("password", ignoreCase = true) ->
                    tr(language, "Текущий пароль указан неверно", "Current password is incorrect")

                error.statusCode == 403 ->
                    tr(language, "Доступно только владельцу аккаунта", "Available to the account owner only")

                error.statusCode == 409 && error.message.contains("alternative login", ignoreCase = true) ->
                    tr(
                        language,
                        "Сначала привяжите e-mail и задайте пароль",
                        "Link an email and set a password first",
                    )

                error.statusCode == 409 && error.message.contains("telegram", ignoreCase = true) ->
                    tr(language, "Этот Telegram уже привязан к другому аккаунту", "This Telegram account is already linked")

                error.statusCode == 409 && error.message.contains("email", ignoreCase = true) ->
                    tr(language, "Такой e-mail уже используется", "This email is already in use")

                error.statusCode == 409 && error.message.contains("username", ignoreCase = true) ->
                    tr(language, "Такое имя пользователя уже занято", "This username is already taken")

                error.statusCode == 409 ->
                    tr(language, "Эти данные уже используются другим аккаунтом", "These details are already used by another account")

                error.statusCode == 429 ->
                    tr(language, "Слишком много попыток. Попробуйте позже", "Too many attempts. Try again later")

                error.statusCode == 422 ->
                    readableRegistrationValidationError(language, error.message)

                error.statusCode == 503 ->
                    tr(language, "Сервис временно недоступен", "The service is temporarily unavailable")

                else -> readableNetworkError(language, error)
            }

            is IOException ->
                tr(language, "Нет соединения с сервером", "Cannot reach the server")

            else ->
                tr(language, "Не удалось сохранить изменения", "Could not save the changes")
        }
    }

    fun readableRegistrationEmailError(
        language: AppLanguage,
        error: Throwable,
    ): String {
        return when (error) {
            is BackendException ->
                if (error.statusCode == 409) {
                    tr(language, "Такой e-mail уже зарегистрирован", "This email is already registered")
                } else {
                    readableRegistrationError(language, error)
                }

            else -> readableRegistrationError(language, error)
        }
    }

    fun readableRegistrationUsernameError(
        language: AppLanguage,
        error: Throwable,
    ): String {
        return when (error) {
            is BackendException ->
                when {
                    error.statusCode == 409 ->
                        tr(language, "Такой username уже занят, попробуйте другой", "This username is taken, try another")

                    error.statusCode == 422 && error.message.contains("unsupported", ignoreCase = true) ->
                        tr(
                            language,
                            "Используйте латиницу, цифры, _, - или .",
                            "Use Latin letters, digits, _, - or .",
                        )

                    else -> readableRegistrationError(language, error)
                }

            else -> readableRegistrationError(language, error)
        }
    }

    fun readableRegistrationCodeError(
        language: AppLanguage,
        error: Throwable,
    ): String {
        return when (error) {
            is BackendException -> when {
                error.message.contains("expired", ignoreCase = true) ->
                    tr(language, "Срок действия кода истек", "Verification code expired")

                error.message.contains("Invalid verification code", ignoreCase = true) ->
                    tr(language, "Неверный код подтверждения", "Invalid verification code")

                error.statusCode == 429 ->
                    tr(
                        language,
                        "Слишком много неверных попыток. Запросите новый код",
                        "Too many invalid attempts. Request a new code",
                    )

                error.statusCode == 422 ->
                    readableRegistrationValidationError(language, error.message)

                else -> readableRegistrationError(language, error)
            }

            else -> readableRegistrationError(language, error)
        }
    }

    fun readablePasswordRecoveryError(
        language: AppLanguage,
        error: Throwable,
    ): String {
        return when (error) {
            is BackendException -> when {
                error.statusCode == 404 ->
                    tr(language, "Пользователь с таким e-mail не найден", "No user found with this email")

                error.statusCode == 429 ->
                    tr(language, "Код уже отправлен недавно, попробуйте позже", "A code was sent recently, try again later")

                error.statusCode == 422 ->
                    readableRegistrationValidationError(language, error.message)

                error.message.contains("Invalid verification code", ignoreCase = true) ->
                    tr(language, "Неверный код подтверждения", "Invalid verification code")

                error.message.contains("expired", ignoreCase = true) ->
                    tr(language, "Срок действия кода истек", "Verification code expired")

                else -> readableNetworkError(language, error)
            }

            else -> readableNetworkError(language, error)
        }
    }

    fun readableInviteError(
        language: AppLanguage,
        error: Throwable,
    ): String {
        return when (error) {
            is BackendException -> when (error.statusCode) {
                404 -> tr(language, "Код приглашения неверный или истек", "Invite code is invalid or expired")
                409 -> tr(language, "Достигнут лимит устройств тарифа", "Device limit reached for the plan")
                422 -> tr(language, "Проверьте код приглашения", "Check invite code")
                else -> readableNetworkError(language, error)
            }

            else -> readableNetworkError(language, error)
        }
    }

    fun readableDeviceNameError(language: AppLanguage, error: Throwable): String =
        if (error is BackendException && error.statusCode == 403) {
            tr(language, "Попробуйте другое имя", "Try another name")
        } else {
            readableNetworkError(language, error)
        }

    fun readableNetworkError(
        language: AppLanguage,
        error: Throwable,
    ): String {
        return when (error) {
            is BackendException -> when {
                error.statusCode == 426 && error.message == "client_update_required" ->
                    readableVpnError(language, "client_update_required")

                error.statusCode == 422 &&
                    error.message.trim() == "Устройство INCY с таким названием уже существует" ->
                    tr(language, "Устройство INCY с таким названием уже существует", "An INCY device with this name already exists")

                error.statusCode == 422 ->
                    tr(language, "Проверьте введенные данные", "Check the entered data")

                isDeviceLimitError(error) -> deviceLimitMessage(language)

                error.message.contains("Device limit", ignoreCase = true) ->
                    tr(language, "Достигнут лимит устройств для текущего тарифа", "Device limit reached for the current plan")

                error.message.contains("Missing bearer token", ignoreCase = true) ->
                    tr(language, "Сессия истекла, войдите снова", "Session expired, please sign in again")

                error.message.contains("subscription", ignoreCase = true) ->
                    tr(language, "Для подключения нужен активный тариф", "An active plan is required to connect")

                error.message.contains("not ready", ignoreCase = true) ->
                    tr(language, "VPN-профиль еще готовится", "Your VPN profile is still being prepared")

                else -> error.message
            }

            is IOException ->
                tr(language, "Нет соединения с сервером", "Cannot reach the server")

            else ->
                tr(language, "Что-то пошло не так. Попробуйте еще раз", "Something went wrong. Please try again")
        }
    }

    fun readableVpnError(
        language: AppLanguage,
        reason: String,
    ): String {
        if (reason == "empty_selected_apps") {
            return tr(language, "Не выбраны приложения", "No apps selected")
        }
        return when (reason) {
            "client_update_required" -> tr(
                language,
                "Обновите Noki для подключения. Обновление доступно в «Безопасности».",
                "Update Noki to connect. The update is available in Security.",
            )
            "underlay_unavailable" -> tr(language, "Нет доступной сети. VPN восстановится автоматически.", "No network available. VPN will reconnect automatically.")
            "permission_denied" -> tr(language, "Разрешение VPN не выдано", "VPN permission was denied")
            "runtime_unavailable" -> tr(language, "VPN-движок недоступен", "VPN runtime is unavailable")
            "interface_error" -> tr(language, "Не удалось создать VPN-интерфейс", "Failed to create VPN interface")
            "core_start_error" -> tr(language, "Не удалось запустить VPN-движок", "Failed to start VPN runtime")
            "runtime_readiness_error" -> tr(
                language,
                "VPN-движок запущен, но трафик через него подтвердить не удалось",
                "VPN runtime started, but traffic through it could not be confirmed",
            )
            "ru_wireguard_exit_probe_failed" -> tr(
                language, "RU-реле подключено, но выходная нода недоступна",
                "RU relay connected, but the exit node is unreachable",
            )
            "ru_multihop_conflict" -> com.noki.vpn.ui.multiHopUnavailableText(language)
            "entry_route_unavailable" -> tr(language, "Выбранный маршрут недоступен", "The selected route is unavailable")
            "entry_route_not_confirmed" -> tr(language, "Входная нода не подтвердила маршрут", "The entry node did not confirm the route")
            "rules_error" -> tr(language, "Не удалось применить правила приложений", "Failed to apply app rules")
            "app_owner_check_unavailable" -> tr(
                language, "Фильтр по приложениям требует Android 10 или новее",
                "App filtering requires Android 10 or later",
            )
            "temporary_vpn_limit" -> tr(
                language,
                "Лимит временных подключений исчерпан",
                "Temporary connection limit reached",
            )
            else -> tr(language, "Подключение не удалось", "Connection failed")
        }
    }

    fun localizeConnectionReason(
        language: AppLanguage,
        reason: String,
    ): String {
        if (reason.isBlank()) return ""
        if (reason == "empty_selected_apps") {
            return tr(language, "Не выбраны приложения", "No apps selected")
        }
        return when (reason) {
            "client_update_required" -> tr(language, "Обновите Noki для подключения", "Update Noki to connect")
            "underlay_unavailable" -> tr(language, "Ожидание сети", "Waiting for network")
            "permission_denied" -> tr(language, "Разрешение не выдано", "Permission denied")
            "temporary_vpn_limit" -> tr(
                language,
                "Лимит временных подключений исчерпан",
                "Temporary connection limit reached",
            )
            "runtime_unavailable" -> tr(language, "Движок недоступен", "Runtime unavailable")
            "interface_error" -> tr(language, "Ошибка интерфейса", "Interface error")
            "core_start_error" -> tr(language, "Ошибка запуска ядра", "Core start error")
            "runtime_readiness_error" -> tr(
                language,
                "Ошибка проверки VPN-трафика",
                "VPN traffic check failed",
            )
            "ru_wireguard_exit_probe_failed" -> tr(
                language, "Нет связи с выходом", "Exit unreachable",
            )
            "ru_multihop_conflict" -> readableVpnError(language, reason)
            "rules_error" -> tr(language, "Ошибка правил", "Rules error")
            "app_owner_check_unavailable" -> readableVpnError(language, reason)
            else -> reason
        }
    }

    fun isTrafficLimitError(error: Throwable): Boolean {
        return error is BackendException &&
            error.message.contains("traffic", ignoreCase = true) &&
            error.message.contains("limit", ignoreCase = true)
    }

    fun isDeviceLimitError(error: Throwable): Boolean {
        return error is BackendException &&
            (
                error.message.contains("device_limit", ignoreCase = true) ||
                    error.message.contains("outside current plan device limit", ignoreCase = true)
            )
    }

    fun isDeviceLimitReason(reason: String): Boolean {
        return reason.equals("device_limit", ignoreCase = true) ||
            reason.contains("outside current plan device limit", ignoreCase = true)
    }

    fun isTrafficLimitReason(reason: String): Boolean {
        return reason.equals("traffic_limit", ignoreCase = true) ||
            reason.equals("free_traffic_limit", ignoreCase = true)
    }

    fun deviceLimitMessage(language: AppLanguage): String {
        return tr(
            language,
            "Устройство не входит в лимит тарифа. Проверьте подключенные устройства.",
            "This device is outside your plan limit. Check connected devices.",
        )
    }

    fun readableRegistrationValidationError(
        language: AppLanguage,
        message: String,
    ): String {
        return when {
            message.contains("verification_code", ignoreCase = true) &&
                (
                    message.contains("at most", ignoreCase = true) ||
                        message.contains("max_length", ignoreCase = true)
                ) -> tr(
                    language,
                    "Код должен быть не длиннее 12 символов",
                    "Code must be at most 12 characters",
                )

            message.contains("verification_code", ignoreCase = true) ->
                tr(language, "Код должен быть не короче 4 символов", "Code must be at least 4 characters")

            message.contains("password", ignoreCase = true) ->
                tr(language, "Пароль должен быть не короче 8 символов", "Password must be at least 8 characters")

            message.contains("username", ignoreCase = true) ->
                tr(
                    language,
                    "Username должен быть не короче 3 символов и использовать латиницу, цифры, _, - или .",
                    "Username must be at least 3 characters and use Latin letters, digits, _, - or .",
                )

            message.contains("email", ignoreCase = true) ->
                tr(language, "Введите корректный e-mail", "Enter a valid email")

            else ->
                tr(language, "Проверьте введенные данные", "Check the entered data")
        }
    }

    private fun tr(
        language: AppLanguage,
        russian: String,
        english: String,
    ): String {
        return if (language == AppLanguage.RU) russian else english
    }
}
