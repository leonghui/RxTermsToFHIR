import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.client.api.IGenericClient

import com.google.common.base.Stopwatch
import com.google.common.collect.HashBasedTable
import com.google.common.collect.HashMultimap
import com.google.common.collect.Multimap
import com.google.common.collect.Table
import org.hl7.fhir.dstu3.model.CodeableConcept
import org.hl7.fhir.dstu3.model.Coding
import org.hl7.fhir.dstu3.model.Medication
import org.hl7.fhir.dstu3.model.Medication.MedicationIngredientComponent
import org.hl7.fhir.dstu3.model.Quantity
import org.hl7.fhir.dstu3.model.Ratio
import org.hl7.fhir.dstu3.model.Substance
import org.hl7.fhir.dstu3.model.Bundle
import org.hl7.fhir.dstu3.model.Bundle.BundleType
import org.hl7.fhir.dstu3.model.Bundle.HTTPVerb
import org.hl7.fhir.dstu3.model.codesystems.MedicationStatus

Stopwatch watch = Stopwatch.createStarted()

FhirContext ctxDstu3 = FhirContext.forDstu3()

String FHIR_SERVER_URL = 'http://server:port/baseDstu3'
String RXNORM_SYSTEM = 'http://www.nlm.nih.gov/research/umls/rxnorm'
String RXNORM_VERSION = '10012018'

// rxTerms release 
String RXTERMS_VERSION = '201809'

// rxNorm concepts
Map<String, CodeableConcept> ingredients = [:]
Map<String, CodeableConcept> doseForms = [:]
Map<String, CodeableConcept> rxNormConcepts = [:]

// rxNorm one-to-one relationships
Map<String, String> hasDoseForm = [:]

// rxNorm one-to-many relationships
Multimap<String, String> hasIngredient = HashMultimap.create()
Multimap<String, String> consistsOf = HashMultimap.create()
Multimap<String, String> tradenameOf = HashMultimap.create()
Multimap<String, String> contains = HashMultimap.create()

// rxNorm attributes
Table<String, String, String> attributes = HashBasedTable.create()

// script containers
List<Medication> meds = []
List<Substance> substances = []

Closure logStart = { String job ->
	watch.reset().start()
	print job + ("\t")
}

Closure logStop = {
	println watch.toString()
}

Closure readRxNormConceptsFile = { 
	
	logStart('Reading RxNorm concepts file')
	
	FileReader cpcRxnConso = new FileReader("src/main/resources/RxNorm_full_$RXNORM_VERSION/prescribe/rrf/RXNCONSO.RRF")
	
	cpcRxnConso.eachLine { String line, int number ->
		
		List<String> tokens = line.split(/\|/)
		
		/* 0	RXCUI 
		 * 1	LAT 
		 * 2	TS 
		 * 3	LUI 
		 * 4	STT 
		 * 5	SUI 
		 * 6	ISPREF 
		 * 7	RXAUI 
		 * 8	SAUI 
		 * 9	SCUI 
		 * 10	SDUI 
		 * 11	SAB 
		 * 12	TTY 
		 * 13	CODE 
		 * 14	STR 
		 * 15	SRL 
		 * 16	SUPPRESS 
		 * 17	CVF 
		 */
	
		switch (tokens.get(12)) {
			case 'DF': // RXCUI, STR
				CodeableConcept doseForm = new CodeableConcept()
					.addCoding(new Coding(RXNORM_SYSTEM, tokens.get(0), tokens.get(14)))
				doseForms.put(tokens.get(0), doseForm)
				break
			case 'IN': // RXCUI, STR
				CodeableConcept ingredient = new CodeableConcept()
					.addCoding(new Coding(RXNORM_SYSTEM, tokens.get(0), tokens.get(14)))
				ingredients.put(tokens.get(0), ingredient)
				break
			case ['SCD', 'SBD', 'GPCK', 'BPCK']: // RXCUI, STR
				CodeableConcept concept = new CodeableConcept()
					.addCoding(new Coding(RXNORM_SYSTEM, tokens.get(0), tokens.get(14)))
				rxNormConcepts.put(tokens.get(0), concept)
				break
		}
	}
	
	cpcRxnConso.close()
	logStop()
}

Closure readRxNormRelationshipsFile = {
	logStart('Reading RxNorm relationships file')
	FileReader cpcRxnRel = new FileReader("src/main/resources/RxNorm_full_$RXNORM_VERSION/prescribe/rrf/RXNREL.RRF")
	
	cpcRxnRel.eachLine { String line, int number ->
		
		List<String> tokens = line.split(/\|/)
		
		/* 0	RXCUI1 
		 * 1	RXAUI1 
		 * 2	STYPE1 
		 * 3	REL 
		 * 4	RXCUI2 
		 * 5	RXAUI2 
		 * 6	STYPE2 
		 * 7	RELA 
		 * 8	RUI 
		 * 9	SRUI 
		 * 10	SAB 
		 * 11	SL 
		 * 12	DIR 
		 * 13	RG 
		 * 14	SUPPRESS 
		 * 15	CVF 
		 */
	
		switch (tokens.get(7)) {
			case 'has_ingredient':
				hasIngredient.put(tokens.get(4), tokens.get(0))
				break
			case 'consists_of':
				consistsOf.put(tokens.get(4), tokens.get(0))
				break
			case 'has_dose_form':
				hasDoseForm.put(tokens.get(4), tokens.get(0))
				break
			case 'tradename_of':
				tradenameOf.put(tokens.get(4), tokens.get(0))
				break
			case 'contains':
				contains.put(tokens.get(4), tokens.get(0))
		}
	}
	
	cpcRxnRel.close()
	logStop()
}

Closure readRxNormAttributesFile = {
	logStart('Reading RxNorm attributes file')
	FileReader cpcRxnSat = new FileReader("src/main/resources/RxNorm_full_$RXNORM_VERSION/prescribe/rrf/RXNSAT.RRF")
	
	cpcRxnSat.eachLine { String line, int number ->
		
		List<String> tokens = line.split(/\|/)
		
		/* 0	RXCUI 
		 * 1	LUI 
		 * 2	SUI 
		 * 3	RXAUI 
		 * 4	STYPE 
		 * 5	CODE 
		 * 6	ATUI 
		 * 7	SATUI 
		 * 8	ATN 
		 * 9	SAB 
		 * 10	ATV 
		 * 11	SUPPRESS 
		 * 12	CVF 
		 */
		
		String attribName = tokens.get(8)
		
		switch (attribName) {
			case [
					'RXN_BOSS_STRENGTH_NUM_VALUE',
					'RXN_BOSS_STRENGTH_NUM_UNIT',
					'RXN_BOSS_STRENGTH_DENOM_VALUE',
					'RXN_BOSS_STRENGTH_DENOM_UNIT'
				]: // RXCUI, ATN, ATV
				attributes.put(tokens.get(0), attribName, tokens.get(10))
				break
		}
	}
	
	cpcRxnSat.close()
	logStop()
}

Closure<MedicationIngredientComponent> getMedicationIngredientComponent = { String scdc_rxCui ->
	MedicationIngredientComponent component = new MedicationIngredientComponent()
	
	Map<String, String> scdcAmount = attributes.row(scdc_rxCui)
	
	if (scdcAmount) {
		String denominatorUnit = scdcAmount.get('RXN_BOSS_STRENGTH_DENOM_UNIT')
		Double denominatorValue = scdcAmount.get('RXN_BOSS_STRENGTH_DENOM_VALUE').toDouble()
		String numeratorUnit = scdcAmount.get('RXN_BOSS_STRENGTH_NUM_UNIT')
		Double numeratorValue = scdcAmount.get('RXN_BOSS_STRENGTH_NUM_VALUE').toDouble()
		
		Ratio amount = new Ratio()
			.setNumerator(new Quantity().setValue(numeratorValue).setUnit(numeratorUnit))
			.setDenominator(new Quantity().setValue(denominatorValue).setUnit(denominatorUnit))
			
		component.setAmount(amount)
	}
	
	String ing_rxCui = hasIngredient.get(scdc_rxCui).first() // assume each SCDC only has one ingredient
	
	component.setItem(ingredients.get(ing_rxCui))

	component.setIsActive(true)
	
	return component
}

Closure readRxTermsFile = {
	logStart('Reading RxTerms file')
	FileReader rxTerms = new FileReader("src/main/resources/RxTerms$RXTERMS_VERSION/RxTerms${RXTERMS_VERSION}.txt")
	
	rxTerms.eachLine { String line, int number ->
		if (number == 1) return
		
		/*
		 * 0	RXCUI
		 * 1	GENERIC_RXCUI
		 * 2	TTY
		 * 3	FULL_NAME
		 * 4	RXN_DOSE_FORM
		 * 5	FULL_GENERIC_NAME
		 * 6	BRAND_NAME
		 * 7	DISPLAY_NAME
		 * 8	ROUTE
		 * 9	NEW_DOSE_FORM
		 * 10	STRENGTH
		 * 11	SUPPRESS_FOR
		 * 12	DISPLAY_NAME_SYNONYM
		 * 13	IS_RETIRED
		 * 14	SXDG_RXCUI
		 * 15	SXDG_TTY
		 * 16	SXDG_NAME
		 * 17	PSN
		 */
		List<String> tokens = line.split(/\|/)
		
		String rxCui = tokens.get(0)
		String tty = tokens.get(2)
		
		Medication med = new Medication()
		
		switch (tty) {
			case ['SBD', 'BPCK']:
				med.setIsBrand(true)
				break
			case ['SCD', 'GPCK']:
				med.setIsBrand(false)
				break
		}

		switch (tty) {
			case ['SBD', 'SCD']:
				consistsOf.get(rxCui).each { String scdc_rxCui ->
					med.addIngredient(getMedicationIngredientComponent(scdc_rxCui))
				}
				break
			case ['BPCK', 'GPCK']:
				contains.get(rxCui).each { String scd_rxCui ->
					consistsOf.get(scd_rxCui).each { String scdc_rxCui ->
						med.addIngredient(getMedicationIngredientComponent(scdc_rxCui))
					}
				}
				break
		}
		
		med.setStatus(Medication.MedicationStatus.ACTIVE)
		med.setForm(doseForms.get(hasDoseForm.get(rxCui)))
		med.setCode(rxNormConcepts.get(rxCui))
		med.setId(rxCui) // use rxCui as resource ID
		meds << med
		
		
	}
	
	rxTerms.close()
	logStop()
}

/*Closure addResourcesToBundle = {
	logStart('Adding resources into a bundle')
	
	meds.each {
		bundle.addEntry()
			.setResource(it)
			.getRequest()
				.setUrl("Medication")
				.setMethod(HTTPVerb.PUT)
	}
	
	logStop()
}*/

Closure loadBundleToServer = {
	
	IGenericClient client = ctxDstu3.newRestfulGenericClient(FHIR_SERVER_URL);
	
	ctxDstu3.getRestfulClientFactory().setConnectTimeout(30 * 1000)
	ctxDstu3.getRestfulClientFactory().setSocketTimeout(60 * 1000)
	
	logStart('Loading bundle to server')
	
	meds.collate(1000).each { batch ->
	
	Bundle input = new Bundle()
	
	batch.each {
		input.setType(BundleType.TRANSACTION)
		input.addEntry()
			.setResource(it)
			.getRequest()
				.setUrl("Medication")
				.setMethod(HTTPVerb.POST)
	}
			
	Bundle response = client.transaction().withBundle(input).execute()

	}
	
	logStop()
}

readRxNormConceptsFile()
readRxNormRelationshipsFile()
readRxNormAttributesFile()
readRxTermsFile()
loadBundleToServer()