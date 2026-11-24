package app.chompass.services

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenFoodFactsMappingTest {
    @Test
    fun analysis_scalesPer100gNutrientsToServing() {
        val product = JSONObject(
            """
            {
              "product_name": "Greek Yogurt",
              "brands": "Acme",
              "serving_quantity": 150,
              "nutriments": {
                "energy-kcal_100g": 80,
                "proteins_100g": 8,
                "carbohydrates_100g": 6,
                "fat_100g": 2,
                "sugars_100g": 4,
                "fiber_100g": 0,
                "sodium_100g": 0.05
              }
            }
            """.trimIndent(),
        )

        val food = OpenFoodFactsService.analysis(product, "3017620422003")
        assertEquals("Acme Greek Yogurt", food.name)
        assertEquals(150.0, food.servingSizeGrams!!, 0.001)
        assertEquals(120, food.calories)
        assertEquals(12.0, food.protein, 0.001)
        assertEquals(9.0, food.carbs, 0.001)
        assertEquals(3.0, food.fat, 0.001)
        assertEquals(6.0, food.sugar!!, 0.001)
        // sodium_100g is grams → milligrams after conversion
        assertEquals(75.0, food.sodium!!, 0.001)
        assertEquals("serving", food.selectedServingUnit)
        assertEquals(1.0, food.selectedServingQuantity!!, 0.001)
        assertEquals("🏷️", food.emoji)
        assertNotNull(food.grounding)
        assertEquals(
            app.chompass.models.NutrientSourceKind.OPEN_FOOD_FACTS,
            food.grounding!!.sourceKind,
        )
        assertEquals("3017620422003", food.grounding!!.sourceId)
    }

    @Test
    fun analysis_prefersServingSpecificNutrientKeys() {
        val product = JSONObject(
            """
            {
              "product_name": "Cola",
              "serving_quantity": 330,
              "nutriments": {
                "energy-kcal_100g": 42,
                "energy-kcal_serving": 139,
                "proteins_serving": 0,
                "carbohydrates_serving": 35,
                "fat_serving": 0
              }
            }
            """.trimIndent(),
        )

        val food = OpenFoodFactsService.analysis(product, "5449000000996")
        assertEquals("Cola", food.name)
        assertEquals(139, food.calories)
        assertEquals(35.0, food.carbs, 0.001)
        assertEquals(0.0, food.protein, 0.001)
    }

    @Test
    fun analysis_usesCarbohydratesTotalWhenCarbsKeyMissing() {
        val product = JSONObject(
            """
            {
              "product_name": "Quick Cooking Rolled Oats",
              "brands": "Bob's Red Mill",
              "serving_quantity": 45,
              "serving_size": "0.5 cup (45 g)",
              "nutriments": {
                "energy-kcal_serving": 180,
                "proteins_serving": 6,
                "fat_serving": 3,
                "carbohydrates-total_serving": 31
              }
            }
            """.trimIndent(),
        )

        val food = OpenFoodFactsService.analysis(product, "0039978033765")
        assertEquals(180, food.calories)
        assertEquals(31.0, food.carbs, 0.001)
        assertEquals(6.0, food.protein, 0.001)
        assertEquals(3.0, food.fat, 0.001)
        assertEquals(45.0, food.servingSizeGrams!!, 0.001)
    }

    @Test
    fun analysis_parsesServingSizeStringWhenQuantityMissing() {
        val product = JSONObject(
            """
            {
              "product_name": "Bread",
              "serving_size": "2 slices (60 g)",
              "nutriments": {
                "energy-kcal_100g": 250,
                "proteins_100g": 9,
                "carbohydrates_100g": 45,
                "fat_100g": 3
              }
            }
            """.trimIndent(),
        )

        val food = OpenFoodFactsService.analysis(product, "123")
        assertEquals(60.0, food.servingSizeGrams!!, 0.001)
        assertEquals(150, food.calories)
        assertEquals("serving", food.selectedServingUnit)
        assertEquals(60.0, food.servingUnitOptions.single().gramsPerUnit, 0.001)
    }

    @Test
    fun analysis_unlabeledServing_hasNoServingOption() {
        val product = JSONObject(
            """
            {
              "product_name": "Mystery",
              "nutriments": {
                "energy-kcal_100g": 80,
                "proteins_100g": 8,
                "carbohydrates_100g": 6,
                "fat_100g": 2
              }
            }
            """.trimIndent(),
        )
        val food = OpenFoodFactsService.analysis(product, "123")
        assertEquals(100.0, food.servingSizeGrams!!, 0.001)
        assertTrue(food.servingUnitOptions.isEmpty())
        org.junit.Assert.assertNull(food.selectedServingUnit)
    }
    @Test
    fun analysis_packageOnly_servingBasisIsThePackage() {
        val product = JSONObject(
            """
            {
              "product_name": "Matcha Oat Drink",
              "brands": "Oatly",
              "quantity": "250 ml",
              "product_quantity": 250,
              "product_quantity_unit": "ml",
              "nutriments": {
                "energy-kcal_100g": 59,
                "proteins_100g": 1.0,
                "carbohydrates_100g": 6.6,
                "fat_100g": 3.0
              }
            }
            """.trimIndent(),
        )
        val food = OpenFoodFactsService.analysis(product, "4250710502227")
        // No labeled serving: the package is the portion basis, not a 100 g
        // guess (Codeberg #168). Macros scale to the package weight.
        assertEquals(250.0, food.servingSizeGrams!!, 0.001)
        // 59 kcal/100g × 2.5, no kJ or Atwater correction.
        assertEquals(148, food.calories)
        assertEquals(2.5, food.protein, 0.001)
        assertEquals(16.5, food.carbs, 0.001)
        assertEquals(7.5, food.fat, 0.001)
        val packageOption = food.servingUnitOptions.single()
        assertEquals("package", packageOption.unit)
        assertEquals(250.0, packageOption.gramsPerUnit, 0.001)
        assertEquals(1.0, packageOption.quantity!!, 0.001)
        assertEquals("package", food.selectedServingUnit)
        assertEquals(1.0, food.selectedServingQuantity!!, 0.001)
    }

    @Test
    fun analysis_estimatedSignQuantity_packageBasisFromDisplayString() {
        // Nutella 400 g jar (device pass 2026-11-12): no structured quantity,
        // display string "400 g e" — the estimated sign must not fall back to
        // the 100 g guess (Codeberg #168).
        val product = JSONObject(
            """
            {
              "product_name": "Nutella",
              "brands": "Ferrero",
              "quantity": "400 g e",
              "nutriments": {
                "energy-kcal_100g": 539,
                "proteins_100g": 6.3,
                "carbohydrates_100g": 57.5,
                "fat_100g": 30.9
              }
            }
            """.trimIndent(),
        )
        val food = OpenFoodFactsService.analysis(product, "3017620422003")
        assertEquals(400.0, food.servingSizeGrams!!, 0.001)
        assertEquals(2156, food.calories)
        assertEquals(25.2, food.protein, 0.001)
        assertEquals(230.0, food.carbs, 0.001)
        assertEquals(123.6, food.fat, 0.001)
        val packageOption = food.servingUnitOptions.single()
        assertEquals("package", packageOption.unit)
        assertEquals(400.0, packageOption.gramsPerUnit, 0.001)
        assertEquals(1.0, packageOption.quantity!!, 0.001)
        assertEquals("package", food.selectedServingUnit)
    }

    @Test
    fun analysis_rejectsMissingNutriments() {
        try {
            OpenFoodFactsService.analysis(
                JSONObject("""{"product_name":"X"}"""),
                "1",
            )
            fail("expected LookupException")
        } catch (e: OpenFoodFactsService.LookupException) {
            assertTrue(e.message!!.contains("incomplete"))
        }
    }

    @Test
    fun analysis_rejectsProductWithNoMacroFields() {
        try {
            OpenFoodFactsService.analysis(
                JSONObject(
                    """
                    {
                      "product_name": "Mystery",
                      "serving_quantity": 100,
                      "nutriments": { "salt_100g": 0.1 }
                    }
                    """.trimIndent(),
                ),
                "1",
            )
            fail("expected LookupException")
        } catch (e: OpenFoodFactsService.LookupException) {
            assertTrue(e.message!!.contains("incomplete"))
        }
    }

    @Test
    fun analysis_preparedOnlyNutrients_useThePreparedBasis() {
        // Codeberg #176: the label gives prepared values only, so OFF stores
        // the product's nutrition under `<nutrient>_prepared*` keys alone.
        val product = JSONObject(
            """
            {
              "product_name": "Instant Noedels Kipsmaak",
              "brands": "YumYum",
              "quantity": "60g",
              "product_quantity": 60,
              "serving_quantity": 380,
              "nutriments": {
                "energy-kcal_prepared_serving": 289,
                "energy-kcal_prepared_100g": 76,
                "proteins_prepared_serving": 4.94,
                "proteins_prepared_100g": 1.3,
                "carbohydrates_prepared_serving": 38,
                "carbohydrates_prepared_100g": 10,
                "fat_prepared_serving": 12.5,
                "fat_prepared_100g": 3.3,
                "sodium_prepared_serving": 1.22,
                "sodium_prepared_100g": 0.32
              }
            }
            """.trimIndent(),
        )

        val food = OpenFoodFactsService.analysis(product, "8852018101024")
        assertEquals("YumYum Instant Noedels Kipsmaak", food.name)
        assertEquals(380.0, food.servingSizeGrams!!, 0.001)
        assertEquals(289, food.calories)
        assertEquals(4.94, food.protein, 0.001)
        assertEquals(38.0, food.carbs, 0.001)
        assertEquals(12.5, food.fat, 0.001)
        assertEquals(1220.0, food.sodium!!, 0.001)
        // The 60 g package is the as-sold weight: not a unit for prepared values.
        assertEquals(listOf("serving"), food.servingUnitOptions.map { it.unit })
        assertEquals("serving", food.selectedServingUnit)
    }

    @Test
    fun analysis_preparedPer100gOnly_portionIs100gNotThePackage() {
        // No labeled serving: the package weight is the as-sold weight, so the
        // portion falls back to 100 g of the prepared food, never to the package.
        val product = JSONObject(
            """
            {
              "product_name": "Pudding mix",
              "product_quantity": 60,
              "nutriments": {
                "energy-kcal_prepared_100g": 76,
                "proteins_prepared_100g": 1.3,
                "carbohydrates_prepared_100g": 10,
                "fat_prepared_100g": 3.3
              }
            }
            """.trimIndent(),
        )

        val food = OpenFoodFactsService.analysis(product, "5449000000996")
        assertEquals(100.0, food.servingSizeGrams!!, 0.001)
        assertEquals(76, food.calories)
        assertTrue(food.servingUnitOptions.isEmpty())
    }

    @Test
    fun analysis_bothBases_prefersAsSold() {
        val product = JSONObject(
            """
            {
              "product_name": "Noodles",
              "serving_quantity": 60,
              "product_quantity": 60,
              "nutriments": {
                "energy-kcal_100g": 450,
                "proteins_100g": 9,
                "carbohydrates_100g": 60,
                "fat_100g": 15,
                "energy-kcal_prepared_100g": 76
              }
            }
            """.trimIndent(),
        )

        val food = OpenFoodFactsService.analysis(product, "3017620422003")
        assertEquals(270, food.calories)
        assertEquals(5.4, food.protein, 0.001)
    }

    @Test
    fun analysis_fallsBackToBarcodeName() {
        val product = JSONObject(
            """
            {
              "serving_quantity": 100,
              "nutriments": {
                "energy-kcal_100g": 10,
                "proteins_100g": 0,
                "carbohydrates_100g": 2,
                "fat_100g": 0
              }
            }
            """.trimIndent(),
        )
        val food = OpenFoodFactsService.analysis(product, "999")
        assertEquals("Barcode 999", food.name)
    }

    @Test
    fun searchHit_scoresPreferNameOverlap() {
        // Construction-only: SearchHit is the search API surface used by GroundingTools.
        val hit = OpenFoodFactsService.SearchHit(
            barcode = "3017620422003",
            name = "Nutella",
            brand = "Ferrero",
            caloriesPer100g = 539.0,
            proteinPer100g = 6.3,
            carbsPer100g = 57.5,
            fatPer100g = 30.9,
            servingGrams = 15.0,
            incompleteEnergy = false,
            score = 9.0,
        )
        assertEquals("3017620422003", hit.barcode)
        assertEquals(539.0, hit.caloriesPer100g!!, 0.01)
        assertFalse(hit.incompleteEnergy)
    }
}
