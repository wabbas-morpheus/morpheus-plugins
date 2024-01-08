package com.morpheusdata.reports

import com.morpheusdata.core.AbstractReportProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ReportResult
import com.morpheusdata.model.ReportType
import com.morpheusdata.model.ReportResultRow
import com.morpheusdata.model.ContentSecurityPolicy
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.response.ServiceResponse

import com.morpheusdata.model.User
import com.morpheusdata.model.Account

import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.util.logging.Slf4j
import io.reactivex.Observable;

import java.sql.Connection

/**
 * Report that list total number of users logins per month. Also includes option to list unique user logins per month
 * This report does not take into account user permissions currently and simply uses a direct SQL Connection using Groovy SQL with RxJava to generate a set of results.
 *
 * A renderer is also defined to render the HTML via Handlebars templates.
 *
 * @author Waqas Abbas
 */
 @Slf4j
class CustomReportProvider extends AbstractReportProvider {
	Plugin plugin
	MorpheusContext morpheusContext
	// int totalCores = 0
	// int totalStorage = 0
	// int totalMemory = 0

	CustomReportProvider(Plugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.morpheusContext = context
	}

	@Override
	MorpheusContext getMorpheus() {
		morpheusContext
	}

	@Override
	Plugin getPlugin() {
		plugin
	}

	@Override
	String getCode() {
		'custom-report-used-cores-storage-memory'
	}

	@Override
	String getName() {
		'TENANT RESOURCE ALLOCATION (Instance + Cluster Host)'
	}

	 ServiceResponse validateOptions(Map opts) {
		 return ServiceResponse.success()
	 }

	/**
	 * Demonstrates building a TaskConfig to get details about the Instance and renders the html from the specified template.
	 * @param instance details of an Instance
	 * @return
	 */
	@Override
	HTMLResponse renderTemplate(ReportResult reportResult, Map<String, List<ReportResultRow>> reportRowsBySection) {
		ViewModel<String> model = new ViewModel<String>()
		def Map<String,Object> my_data = [totalCores:reportRowsBySection.header[0].dataMap.TotalCores,
										  totalStorage:reportRowsBySection.header[0].dataMap.TotalStorage,
										  totalMemory:reportRowsBySection.header[0].dataMap.TotalMemory,
										  items:reportRowsBySection.main]

		model.object = my_data 
		getRenderer().renderTemplate("hbs/reportResults", model)
			
		
	}

	/**
	 * Allows various sources used in the template to be loaded
	 * @return
	 */
	@Override
	ContentSecurityPolicy getContentSecurityPolicy() {
		def csp = new ContentSecurityPolicy()
		csp.scriptSrc = '*.jsdelivr.net'
		csp.frameSrc = '*.digitalocean.com'
		csp.imgSrc = '*.wikimedia.org'
		csp.styleSrc = 'https: *.bootstrapcdn.com'
		csp
	}


	void process(ReportResult reportResult) {
		morpheus.report.updateReportResultStatus(reportResult,ReportResult.Status.generating).blockingGet();
		Long displayOrder = 0
		int totalCores = 0
		int totalStorage = 0
		int totalMemory = 0
		
		List<GroovyRowResult> instanceResults = []
		List<GroovyRowResult> clusterHostsResults = []


		
		Account reportAccount = reportResult.getAccount() //Get current account details
		
		withDbConnection { Connection dbConnection ->

			//Get the current users account (tenant) ID by account name
			int currentAccountId = new Sql(dbConnection).rows("select id from account where name='"+reportAccount?.getName()+"';")[0].id
			log.info("Current Tenant ID = ${currentAccountId}")
			String dbQParam ="''" 
			if (reportResult.configMap?.tenantId && currentAccountId ==1){ //If master tenant then run report for specified account id on the UI
					dbQParam ="'${reportResult.configMap?.tenantId}'"
			}else{//If running the report from the subtenant then only scope it to that subtenant 
				dbQParam ="'${currentAccountId}'"
			} 

			//Get Instances
			instanceResults = new Sql(dbConnection).rows("select id,concat(name,' (Instance)')as name,max_cores,max_storage,max_memory,account_id as tenant_id from instance where account_id="+dbQParam+";")
			
			//Get Cluster hosts
			clusterHostsResults = new Sql(dbConnection).rows("select cs.id,concat(cs.name,' (cluster host)')as name,cs.max_cores,cs.max_storage,cs.max_memory,cs.account_id as tenant_id from compute_server cs join compute_server_group csg on cs.server_group_id=csg.id where cs.account_id="+dbQParam+";")	
			
			//Combine the results 
			instanceResults.addAll(clusterHostsResults)
			
			//Calculate the totals
			instanceResults.collect{
				totalCores +=it.max_cores
				totalStorage +=it.max_storage.intdiv(1024).intdiv(1024).intdiv(1024)
				totalMemory +=it.max_memory.intdiv(1024).intdiv(1024).intdiv(1024)
				}
			
		}

		//Create a row record to show the totals on the header section of the reports template
		def Map<String,Object> headData = [:]
		headData = [TotalCores:totalCores.toString(),
					TotalStorage:totalStorage.toString(),
					TotalMemory:totalMemory.toString()]
		ReportResultRow resultRowRecordHead = new ReportResultRow(section: ReportResultRow.SECTION_HEADER, displayOrder: displayOrder++, dataMap: headData)
		morpheus.report.appendResultRows(reportResult,[resultRowRecordHead]).blockingGet()
		
		//Prepare the list of instances and cluster hosts for the reports template
		Observable<GroovyRowResult> observable = Observable.fromIterable(instanceResults) as Observable<GroovyRowResult>
		observable.map{ resultRow ->
			log.info("Mapping resultRow ${resultRow}")
			def Map<String,Object> data = [:]
			
			
			data = [Id: resultRow.id.toString(),
					ObjectName: resultRow.name.toString(),
					MaxCores: resultRow.max_cores.toString(),
					MaxStorage: resultRow.max_storage.intdiv(1024).intdiv(1024).intdiv(1024).toString(),
					MaxMem: resultRow.max_memory.intdiv(1024).intdiv(1024).intdiv(1024).toString(),
					TenantId:resultRow.tenant_id.toString()]
			
			
			
			ReportResultRow resultRowRecord = new ReportResultRow(section: ReportResultRow.SECTION_MAIN, displayOrder: displayOrder++, dataMap: data)
			//log.info("resultRowRecord: ${resultRowRecord.dump()}")
			//resultRowRecord.setSection(section: ReportResultRow.SECTION_HEADER, displayOrder: displayOrder++, dataMap: data)
			return resultRowRecord
		}.buffer(50).doOnComplete {
			morpheus.report.updateReportResultStatus(reportResult,ReportResult.Status.ready).blockingGet();
		}.doOnError { Throwable t ->
			morpheus.report.updateReportResultStatus(reportResult,ReportResult.Status.failed).blockingGet();
		}.subscribe {resultRows ->
			morpheus.report.appendResultRows(reportResult,resultRows).blockingGet()
		}

	}

	 @Override
	 String getDescription() {
		 return "Report total number of resources (CPU,Storage,Memory) by tenant"
	 }

	 @Override
	 String getCategory() {
		 return 'inventory'
	 }

	 @Override
	 Boolean getOwnerOnly() {
		 return false
	 }

	 @Override
	 Boolean getMasterOnly() {
		 return false
	 }

	 @Override
	 Boolean getSupportsAllZoneTypes() {
		 return true
	 }

	 OptionType tenantIdTextField = new OptionType(
	 										code: 'tenant-id-text-field', 
	 										name: 'Tenant Id', 
	 										fieldName: 'tenantId', 
	 										fieldContext: 'config', 
	 										fieldLabel: 'Tenant Id', 
	 										displayOrder: 0,
	 										helpBlock:'(Optional) - Specify the tenant id if running the report from the master tenant.'
	 										)



	 @Override
	 List<OptionType> getOptionTypes() {
		 [tenantIdTextField]
	 }
 }
