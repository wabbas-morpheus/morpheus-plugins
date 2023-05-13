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
		'custom-report-instance-cost-all-tenants'
	}

	@Override
	String getName() {
		'Report Morpheus Instance Cost Per Month'
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
		model.object = reportRowsBySection
		getRenderer().renderTemplate("hbs/instanceCostReport", model)
			
		
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
		List<GroovyRowResult> results = []
		withDbConnection { Connection dbConnection ->

			
			if(reportResult.configMap?.instanceTags) {
				String tagsText = "${reportResult.configMap?.instanceTags}"
				String qParam =""
				def tags = tagsText.split(',')
				def i =1;
				for( String tag : tags ){ // create query based on instance tags provided as comma seprated list
      				def t = tag.split(':')
      				if (i==1){
      					qParam = "select i.id from instance i where i.id in (select imt.instance_metadata_id from instance_metadata_tag imt where imt.metadata_tag_id in (select mt.id from metadata_tag mt where mt.name='"+t[0]+"' and mt.value='"+t[1]+"'))"
      				}else{
      					qParam = qParam + " and i.id in (select imt.instance_metadata_id from instance_metadata_tag imt where imt.metadata_tag_id in (select mt.id from metadata_tag mt where mt.name='"+t[0]+"' and mt.value='"+t[1]+"'))"
      				}

                 i++
				}
				//log.info("qParam = "+qParam)
				results = new Sql(dbConnection).rows("select ref_id as instance_id, ref_name as instance_name,account_name as tenant,zone_name as cloud,CONCAT(ROUND(actual_running_price,2),' ',actual_currency) as month_to_date, CONCAT(ROUND(actual_total_price,2),' ',actual_currency) as total,CONCAT(year(last_cost_date),' ',monthname(last_cost_date))period from account_invoice where  instance_id IN ("+qParam+") order by last_cost_date DESC,instance_id;")
				
			} else { // tags not specified

				results = new Sql(dbConnection).rows("select ref_id as instance_id, SUBSTRING(ref_name,1,32) as instance_name,SUBSTRING(account_name,1,32) as tenant,SUBSTRING(zone_name,1,32) as cloud,CONCAT(ROUND(actual_running_price,2),' ',actual_currency) as month_to_date, CONCAT(ROUND(actual_total_price,2),' ',actual_currency) as total,CONCAT(year(last_cost_date),' ',monthname(last_cost_date))period from account_invoice where ref_type='instance' order by last_cost_date DESC,instance_id;")
				
			}
		}

		//log.info("Results: ${results}")
		Observable<GroovyRowResult> observable = Observable.fromIterable(results) as Observable<GroovyRowResult>
		observable.map{ resultRow ->
			//log.info("Mapping resultRow ${resultRow}")
			def Map<String,Object> data = [:]
			
			data = [InstanceID: resultRow.instance_id, 
			        InstanceName: resultRow.instance_name,
			        Tenant: resultRow.tenant,
			        Cloud: resultRow.cloud,
			        MonthToDate: resultRow.month_to_date,
			        Total: resultRow.total,
			        Period:resultRow.period ]
			
			
			
			ReportResultRow resultRowRecord = new ReportResultRow(section: ReportResultRow.SECTION_MAIN, displayOrder: displayOrder++, dataMap: data)
			//log.info("resultRowRecord: ${resultRowRecord.dump()}")
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
		 return "Report to cost"
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
		 return true
	 }

	 @Override
	 Boolean getSupportsAllZoneTypes() {
		 return true
	 }

	 OptionType instanceTagsTextField = new OptionType(
	 										code: 'instance-tags-text-field', 
	 										name: 'Instance Tags', 
	 										fieldName: 'instanceTags', 
	 										fieldContext: 'config', 
	 										fieldLabel: 'Instance Tags', 
	 										displayOrder: 0,
	 										helpBlock:'Optional - Specify a comma separated list of tags for example. Tag1:Value1,Tag2:Value2'
	 										)





	 @Override
	 List<OptionType> getOptionTypes() {
		 [instanceTagsTextField]
	 }
 }
