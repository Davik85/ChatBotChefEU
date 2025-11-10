package app.util

sealed interface MainMenuAction {
    object Recipes : MainMenuAction
    object Calorie : MainMenuAction
    object Ingredient : MainMenuAction
    object Help : MainMenuAction
}

fun parseMainMenuCallbackData(data: String?): MainMenuAction? {
    return when (data) {
        "mode:recipes" -> MainMenuAction.Recipes
        "mode:calorie" -> MainMenuAction.Calorie
        "mode:ingredient" -> MainMenuAction.Ingredient
        "mode:help" -> MainMenuAction.Help
        else -> null
    }
}
