param(
    [string]$BaseUrl = 'http://localhost:8080'
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

function Invoke-Api {
    param([string]$Method, [string]$Path, [hashtable]$Body)

    $request = @{
        Method = $Method
        Uri = "$BaseUrl/api/v1$Path"
        TimeoutSec = 15
    }
    if ($null -ne $Body) {
        $request.ContentType = 'application/json'
        $request.Body = $Body | ConvertTo-Json
    }
    Invoke-RestMethod @request
}

function Find-DemoRecord {
    param($Records, [string]$Field, [string]$Value)

    $matches = @($Records | Where-Object { $_.$Field -eq $Value })
    if ($matches.Count -ne 1) {
        throw "Expected one available $Field=$Value. Start with the demo profile and check bed occupancy."
    }
    $matches[0]
}

function Assert-Demo {
    param([bool]$Condition, [string]$Message)

    if (-not $Condition) { throw $Message }
}

function Assert-BedOccupancy {
    param([string]$BedId, [bool]$Expected)

    $bed = Invoke-Api GET "/beds/$BedId"
    Assert-Demo ($bed.occupied -eq $Expected) "Unexpected occupancy for bed $BedId."
}

function Get-EventTime {
    [DateTimeOffset]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'")
}

Write-Host '1. Look up demo departments, wards, and available beds.'
$departments = Invoke-Api GET '/departments?active=true'
$wards = Invoke-Api GET '/wards?active=true'
$firstDepartment = Find-DemoRecord $departments 'departmentCode' 'DEMO-MED'
$secondDepartment = Find-DemoRecord $departments 'departmentCode' 'DEMO-REHAB'
$firstWard = Find-DemoRecord $wards 'wardCode' 'DEMO-WARD-1'
$secondWard = Find-DemoRecord $wards 'wardCode' 'DEMO-WARD-2'
$firstBed = Find-DemoRecord (Invoke-Api GET "/beds?wardId=$($firstWard.id)&active=true&occupied=false") 'bedNumber' '01'
$secondBed = Find-DemoRecord (Invoke-Api GET "/beds?wardId=$($secondWard.id)&active=true&occupied=false") 'bedNumber' '01'

Write-Host '2. Register a fictional patient, then cancel an admission before department entry.'
$runId = [guid]::NewGuid().ToString('N')
$patient = Invoke-Api POST '/patients' @{
    medicalRecordNumber = "DEMO-$runId"
    firstName = 'Demo'
    lastName = 'Patient'
    dateOfBirth = '1990-05-14'
}
$cancelledAdmission = Invoke-Api POST '/encounters' @{
    patientId = $patient.id
    encounterNumber = "DEMO-CANCEL-$runId"
    admittedAt = Get-EventTime
}
$cancelledAdmission = Invoke-Api POST "/encounters/$($cancelledAdmission.id)/admission-cancellations" @{
    cancelledAt = Get-EventTime
    cancelledBy = 'demo-clerk'
}
Assert-Demo ($cancelledAdmission.status -eq 'ADMISSION_CANCELLED') 'Admission was not cancelled.'
$cancelledTimeline = Invoke-Api GET "/encounters/$($cancelledAdmission.id)/timeline"
Assert-Demo ($cancelledTimeline.locations.Count -eq 0 -and $cancelledTimeline.discharges.Count -eq 0) 'Cancelled admission should have no location or discharge history.'

Write-Host '3. Admit the same patient again and enter the first ward.'
$encounter = Invoke-Api POST '/encounters' @{
    patientId = $patient.id
    encounterNumber = "DEMO-VISIT-$runId"
    admittedAt = Get-EventTime
}
$encounterPath = "/encounters/$($encounter.id)"
$null = Invoke-Api POST "$encounterPath/department-admissions" @{
    departmentId = $firstDepartment.id
    wardId = $firstWard.id
    bedId = $firstBed.id
    startedAt = Get-EventTime
}
Assert-BedOccupancy $firstBed.id $true

Write-Host '4. Transfer to the second ward; the first bed becomes available.'
$secondLocation = Invoke-Api POST "$encounterPath/transfers" @{
    departmentId = $secondDepartment.id
    wardId = $secondWard.id
    bedId = $secondBed.id
    transferredAt = Get-EventTime
}
Assert-BedOccupancy $firstBed.id $false
Assert-BedOccupancy $secondBed.id $true

Write-Host '5. Discharge, then cancel discharge and restore the second bed.'
$discharged = Invoke-Api POST "$encounterPath/discharges" @{
    dischargedAt = Get-EventTime
}
Assert-Demo ($discharged.status -eq 'DISCHARGED') 'Encounter was not discharged.'
Assert-BedOccupancy $secondBed.id $false
$restored = Invoke-Api POST "$encounterPath/discharge-cancellations" @{
    cancelledAt = Get-EventTime
    cancelledBy = 'demo-clerk'
}
Assert-Demo ($restored.status -eq 'IN_DEPARTMENT' -and $null -eq $restored.dischargedAt) 'Discharge cancellation did not restore the encounter.'
Assert-BedOccupancy $secondBed.id $true
$restoredTimeline = Invoke-Api GET "$encounterPath/timeline"
$currentLocations = @($restoredTimeline.locations | Where-Object { $null -eq $_.endedAt })
Assert-Demo ($restoredTimeline.discharges.Count -eq 1 -and $currentLocations.Count -eq 1) 'Expected one discharge audit and one current location.'
$audit = $restoredTimeline.discharges[0]
Assert-Demo ($audit.locationId -eq $secondLocation.id -and $audit.restoredLocationId -eq $currentLocations[0].id) 'Discharge audit does not link the original and restored locations.'
Assert-Demo ($audit.cancelledBy -eq 'demo-clerk' -and $null -ne $audit.cancelledAt) 'Missing discharge cancellation audit.'
Assert-Demo ([DateTimeOffset]$currentLocations[0].startedAt -eq [DateTimeOffset]$discharged.dischargedAt) 'Restored location must continue from the original discharge time.'

Write-Host '6. Discharge again and inspect the completed encounter timeline.'
$completed = Invoke-Api POST "$encounterPath/discharges" @{
    dischargedAt = Get-EventTime
}
Assert-Demo ($completed.status -eq 'DISCHARGED') 'Final discharge failed.'
Assert-BedOccupancy $firstBed.id $false
Assert-BedOccupancy $secondBed.id $false
$timeline = Invoke-Api GET "$encounterPath/timeline"
Assert-Demo ($timeline.encounter.status -eq 'DISCHARGED' -and $timeline.locations.Count -eq 3 -and $timeline.discharges.Count -eq 2) 'Unexpected final timeline.'
Assert-Demo (@($timeline.locations | Where-Object { $null -eq $_.endedAt }).Count -eq 0) 'A location is still open after discharge.'
Assert-Demo (@($timeline.discharges | Where-Object { $null -eq $_.cancelledAt }).Count -eq 1) 'Expected one effective discharge.'

[ordered]@{
    patientId = $patient.id
    cancelledAdmissionId = $cancelledAdmission.id
    encounterId = $encounter.id
    timelineUrl = "$BaseUrl/api/v1$encounterPath/timeline"
    timeline = $timeline
} | ConvertTo-Json -Depth 8
