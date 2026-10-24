package app.pdfdoc.domain.form

/** A fillable AcroForm field. [name] is the fully qualified field name used as the key. */
sealed interface FormField {
    val name: String
    val label: String
    val page: Int
}

data class TextFormField(
    override val name: String,
    override val label: String,
    override val page: Int,
    val value: String,
    val multiline: Boolean,
    val maxLength: Int?,
) : FormField

data class CheckFormField(
    override val name: String,
    override val label: String,
    override val page: Int,
    val checked: Boolean,
) : FormField

data class ChoiceOption(val export: String, val display: String)

data class ChoiceFormField(
    override val name: String,
    override val label: String,
    override val page: Int,
    val options: List<ChoiceOption>,
    val value: String?,
    val editable: Boolean,
) : FormField

data class RadioFormField(
    override val name: String,
    override val label: String,
    override val page: Int,
    val options: List<String>,
    val value: String?,
) : FormField

/** Signature fields, buttons and anything else that can't be filled with a value. */
data class UnsupportedFormField(
    override val name: String,
    override val label: String,
    override val page: Int,
) : FormField
