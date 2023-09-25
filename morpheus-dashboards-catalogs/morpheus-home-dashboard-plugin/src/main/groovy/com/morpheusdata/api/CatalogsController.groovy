package com.morpheusdata.api
import com.morpheusdata.model.Permission
import com.morpheusdata.model.projection.InstanceIdentityProjection
import com.morpheusdata.views.JsonResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.PluginController
import com.morpheusdata.web.Route
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.MorpheusContext
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.util.logging.Slf4j

import java.sql.Connection



@Slf4j
class CatalogsController implements PluginController {

  MorpheusContext morpheusContext
  Plugin plugin

  public CatalogsController(Plugin plugin, MorpheusContext morpheusContext) {
    this.plugin = plugin
    this.morpheusContext = morpheusContext
  }

  @Override
  public String getCode() {
    return 'catalogs-controller'
  }

  @Override
  String getName() {
    return 'Catalogs Controller'
  }

  @Override
  MorpheusContext getMorpheus() {
    return morpheusContext
  }

  List<Route> getRoutes() {
    log.info("Get Routes")
    [
            Route.build("/catalogs", "catalogs", [Permission.build("provisioning", "full")]),
            //Route.build("/accounts/count", "count", [Permission.build("accounts-api", "full")]),
    ]
  }

  // public getPermissions() {
  //   log.info("Getting permissions")
  //   return [
  //           new Permission('Catalogs Widget Plugin', 'catalogs-api', [Permission.AccessType.none, Permission.AccessType.read, Permission.AccessType.full]),
  //   ]
  // }


  

  def catalogs(ViewModel <Map> model){

    def rtn = [:]
    Connection dbConnection
    def masterAccount = false
    if(model.user?.account?.masterAccount) {
      masterAccount = true
    }
    if (masterAccount) {
      try {
        dbConnection = morpheus.report.getReadOnlyDatabaseConnection().blockingGet()
        rtn.catalogs = new Sql(dbConnection).rows("select id,name,visibility from catalog_item_type limit 10;")
        //rtn.catalogs= [[id:1,name:"Cat1"]]
        
      } catch (e){
        log.error("error getting catalogs: ${e}", e)
      } finally {
        morpheus.report.releaseDatabaseConnection(dbConnection).blockingGet()
      }
    }
    return JsonResponse.of(rtn)
  }


}