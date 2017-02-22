package com.advancedtelematic.director.data

object Utility {
  import io.circe._

  implicit class ToCanonicalJsonOps(value: Json) {
    // TODO: Get is from java ;)
    def canonicalBytes: Array[Byte] = canonical.getBytes
    def canonical: String = generate(value).noSpaces

    private def generate(value: Json): Json = value.arrayOrObject[Json](
      value,
      array => Json.fromValues(array.map(generate)),
      obj => Json.fromJsonObject(
        JsonObject.fromIterable {
          obj.toList
            .map { case (k, v) => k -> generate(v)
          }.sortBy(_._1)
        }
      )
    )
  }
}

protected[data] object ValidationUtils {
  import eu.timepit.refined.api.Validate

  def validHex(length: Option[Long], str: String): Boolean = {
    length.forall(str.length == _) && str.forall(h => ('0' to '9').contains(h) || ('a' to 'f').contains(h))
  }

  def validHexValidation[T](specificLength: Option[Long], proof: T): Validate.Plain[String, T] =
    Validate.fromPredicate(
      str => validHex(specificLength, str),
      str => s"$str is not a ${specificLength.getOrElse("")} hex string",
      proof
    )

  def validInBetween[T](min: Long, max: Long, proof: T): Validate.Plain[String, T] =
    Validate.fromPredicate(
      str => str.length >= min && str.length <= max,
      str => s"$str is not between $min and $max chars long",
      proof
    )

  def validHash[T](length: Long, name: String, proof: T): Validate.Plain[String, T] =
    Validate.fromPredicate(
      hash => validHex(Some(length),hash),
      hash => s"$hash must be a $name",
      proof
    )
}
